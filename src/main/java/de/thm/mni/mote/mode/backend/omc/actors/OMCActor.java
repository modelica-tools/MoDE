package de.thm.mni.mote.mode.backend.omc.actors;

import de.thm.mni.mhpp11.smbj.actors.AbstractActor;
import de.thm.mni.mhpp11.smbj.logging.messages.ErrorMessage;
import de.thm.mni.mhpp11.smbj.messages.ErrorExitMessage;
import de.thm.mni.mhpp11.smbj.messages.ExitMessage;
import de.thm.mni.mhpp11.smbj.messages.base.Message;
import de.thm.mni.mote.mode.backend.file.messages.FileChangedMessage;
import de.thm.mni.mote.mode.backend.messages.SetProjectMessage;
import de.thm.mni.mote.mode.backend.messages.UnsetProjectMessage;
import de.thm.mni.mote.mode.backend.omc.OMCException;
import de.thm.mni.mote.mode.backend.omc.OMCUtilities;
import de.thm.mni.mote.mode.backend.omc.OMCompiler;
import de.thm.mni.mote.mode.backend.omc.messages.*;
import de.thm.mni.mote.mode.config.Settings;
import de.thm.mni.mote.mode.config.model.Modelica;
import de.thm.mni.mote.mode.config.model.Project;
import de.thm.mni.mote.mode.modelica.MoContainer;
import de.thm.mni.mote.mode.modelica.MoLater;
import de.thm.mni.mote.mode.modelica.MoRoot;
import de.thm.mni.mote.mode.parser.ParserException;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static de.thm.mni.mote.mode.util.Translator.tr;

/**
 * Created by Marcel Hoppe on 23.01.17.
 */
@Getter
public class OMCActor extends AbstractActor {
  OMCompiler omc = null;
  Boolean started = false;
  
  private Project project;
  
  private ObservableList<MoContainer> data = FXCollections.observableArrayList();
  private MoRoot mrSystemLibraries = new MoRoot("system_libraries");
  private MoRoot mrProjectLibraries = new MoRoot("project_libraries");
  private MoRoot mrProject = new MoRoot("project");
  
  {
    MoContainer.getROOTS().add(mrSystemLibraries);
    MoContainer.getROOTS().add(mrProjectLibraries);
    MoContainer.getROOTS().add(mrProject);
  }
  
  private enum LOAD_TYPE {
    SYSTEM_LIBS,
    PROJECT_LIBS,
    PROJECT
  }
  
  private int loadStatus = 0;
  
  private ExecutorService es = Executors.newCachedThreadPool();
  
  public OMCActor(UUID id) {
    super(id);
    data.addAll(mrSystemLibraries, mrProjectLibraries, mrProject);
  }
  
  private void startOMC(StartOMCMessage msg) {
    Settings settings = Settings.load();
    Modelica m = settings.getModelica();
    try {
      omc = new OMCompiler(getID(), m.getCompiler(), settings.getLang());
      started = true;
      msg.answer(getID(), null);
    } catch (Exception e) {
      msg.error(getID(), null);
      started = false;
      send(new ErrorMessage(OMCActor.class, getID(), new OMCException(tr("Error", "error.omcactor.cant_start_omc"), e)));
    }
  }
  
  private void setProject(SetProjectMessage msg) {
    try {
      if(this.project != null) throw new IllegalStateException("project already set");
      
      this.project = msg.getPayload();
      msg.answer(getID(), this.project);
      
      loadStatus = LoadStatusOMCMessage.STATUS.START.ordinal();
      send(new LoadStatusOMCMessage(getID(), LoadStatusOMCMessage.STATUS.START, loadStatus));
      
      omc.addSystemLibraries(project.getSystemLibraries());
      collectDataInBackground(LOAD_TYPE.SYSTEM_LIBS);
  
      omc.loadProjectLibraries(project.getMoFile());
      collectDataInBackground(LOAD_TYPE.PROJECT_LIBS);
  
      omc.setProject(project.getMoFile());
      collectDataInBackground(LOAD_TYPE.PROJECT);
      
    } catch (IllegalStateException | ParserException e) {
      msg.error(getID(), e);
      send(new ErrorMessage(this.getClass(), getID(), e));
    }
  }
  
  private void unsetProject() {
    try {
      if(this.project == null) throw new IllegalStateException("project not set");
      this.project = null;
      omc.clearProject();
    } catch (ParserException | IllegalStateException e) {
      send(new ErrorMessage(this.getClass(), getID(), e));
    }
  }
  
  private void updateClass(MoContainer container) {
    try {
      container.update(omc);
    } catch (Exception e) {
      send(new ErrorMessage(OMCActor.class, getID(), e));
    }
  }
  
  private void collectDataInBackground(LOAD_TYPE type) {
    es.execute(() -> {
      LoadStatusOMCMessage.STATUS status = null;
      switch (type) {
        case SYSTEM_LIBS:
          data.get(0).getChildren().clear();
          OMCUtilities.lightCollect(this.omc, data.get(0), this.omc.getSystemLibraries());
          status = LoadStatusOMCMessage.STATUS.SYSTEM_LIB_READY;
          break;
        case PROJECT_LIBS:
          data.get(1).getChildren().clear();
          OMCUtilities.lightCollect(this.omc, data.get(1), this.omc.getProjectLibraries());
          status = LoadStatusOMCMessage.STATUS.PROJECT_LIB_READY;
          break;
        case PROJECT:
          data.get(2).getChildren().clear();
          OMCUtilities.lightCollect(this.omc, data.get(2), this.omc.getProject());
          status = LoadStatusOMCMessage.STATUS.PROJECT_READY;
          break;
      }
      loadStatus += status.ordinal();
      send(new LoadStatusOMCMessage(getID(), status, loadStatus));
  
      if (LoadStatusOMCMessage.STATUS.isComplete(loadStatus)) {
        send(new LoadStatusOMCMessage(getID(), LoadStatusOMCMessage.STATUS.COMPLETE));
      }
    });
  }
  
  private void projectChanged(FileChangedMessage msg) {
    try {
      
      Path path = msg.getPayload();
      
      if(Files.isDirectory(path)) {
        if(!Files.exists(path.resolve("package.mo"))) return;
      }
      
      if(msg.getType().equals(FileChangedMessage.TYPE.CREATED)) {
        omc.reloadProject();
        elementCreated(path);
      } else if (msg.getType().equals(FileChangedMessage.TYPE.MODIFIED)) {
        if (Files.isDirectory(path)) return;
        omc.reloadProject();
  
        elementModified(path);
      } else if (msg.getType().equals(FileChangedMessage.TYPE.DELETED)) {
        omc.reloadProject();
        elementDeleted(path);
      }
      
    } catch (ParserException e) {
      send(new ErrorMessage(OMCActor.class, getID(), e));
    }
  }
  
  private void elementCreated(Path path) {
    Path projectPath = project.getMoFile().getParent().getParent();
    MoContainer parent = MoContainer.staticFind(projectPath.relativize(path).getParent().toString().replaceAll("/", "."));
    
    if (parent == null) {
      send(new ErrorMessage(OMCActor.class, getID(), new NoSuchElementException("No such parent")));
      return;
    }
  
    OMCUtilities.lightCollect(getOmc(), parent.getParent(), parent.getSimpleName());
    parent.update(getOmc());
  }
  
  private void elementModified(Path path) {
    Path projectPath = project.getMoFile().getParent().getParent();
  
    MoContainer parent = MoContainer.staticFind(projectPath.relativize(path).getParent().toString().replaceAll("/", "."));
    if (parent == null) {
      send(new ErrorMessage(OMCActor.class, getID(), new NoSuchElementException("No such parent")));
      return;
    }

    if(path.endsWith("package.mo")) {
      
      parent.removeNotExistingChildren(getOmc().getChildren(parent.getName()));
      elementCreated(path.getParent());
    } else if(path.toString().endsWith(".mo")) {
      List<MoContainer> list = parent.getChildren().stream().filter(
          container -> container.getElement().getClassInformation().getFileName().equals(path)).collect(
              Collectors.toList());
      list.forEach(element -> {
        element.setElement(new MoLater()).getElement();
        parent.getChildren().set(parent.getChildren().indexOf(element), element);
      });
    }
  }
  
  private void elementDeleted(Path path) {
    Path projectPath = project.getMoFile().getParent().getParent();
    MoContainer parent = MoContainer.staticFind(projectPath.relativize(path).getParent().toString().replaceAll("/", "."));
  
    if (parent == null) {
      send(new ErrorMessage(OMCActor.class, getID(), new NoSuchElementException("No such parent")));
      return;
    }

    parent.removeNotExistingChildren(getOmc().getChildren(parent.getName()));
  }
  
  @Override
  public void execute(Message msg) {
    if (msg instanceof StartOMCMessage) {
      startOMC((StartOMCMessage)msg);
    } else if (msg instanceof ExitMessage || msg instanceof ErrorExitMessage) {
      if (omc != null) {
        omc.disconnect();
        started = false;
      }
    } else if(msg instanceof SetProjectMessage || msg instanceof FileChangedMessage || msg instanceof OMCMessage || msg instanceof UnsetProjectMessage) {
      if (!started) send(new ErrorMessage(OMCActor.class, getID(), new IllegalStateException(tr("Error", "error.omcactor.omc_not_running"))));
      else {
        if (msg instanceof SetProjectMessage) {
          setProject((SetProjectMessage) msg);
        } else if (msg instanceof UnsetProjectMessage) {
          unsetProject();
        } else if(msg instanceof FileChangedMessage) {
          projectChanged((FileChangedMessage) msg);
        } else if (msg instanceof GetDataOMCMessage) {
          ((GetDataOMCMessage)msg).answer(getID(), FXCollections.unmodifiableObservableList(getData()));
        } else if (msg instanceof UpdateClassOMCMessage) {
          updateClass(((UpdateClassOMCMessage) msg).getPayload());
        } else if (msg instanceof GetAvailableLibsOMCMessage) {
          ((GetAvailableLibsOMCMessage) msg).answer(getID(), omc.getAvailableLibraries());
        }
      }
    }
  }
  
  @Override
  public void onStop(Message msg) {
    super.onStop(msg);
    es.shutdownNow();
    if(msg instanceof ErrorExitMessage) {
      try {
        es.awaitTermination(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }
}
