package de.thm.mni.mote.mode.uiactor.editor.statemachine.elements;

import de.thm.mni.mote.mode.modelica.MoConnection;
import de.thm.mni.mote.mode.modelica.MoVariable;
import de.thm.mni.mote.mode.modelica.graphics.MoLine;
import de.thm.mni.mote.mode.parser.ParserException;
import de.thm.mni.mote.mode.uiactor.control.modelica.MoDiagramGroup;
import de.thm.mni.mote.mode.uiactor.control.modelica.MoIconGroup;
import de.thm.mni.mote.mode.uiactor.editor.actionmanager.commands.Command;
import de.thm.mni.mote.mode.uiactor.editor.statemachine.StateMachine;
import de.thm.mni.mote.mode.uiactor.editor.statemachine.interfaces.Actionable;
import de.thm.mni.mote.mode.uiactor.editor.statemachine.interfaces.Addable;
import de.thm.mni.mote.mode.uiactor.editor.statemachine.interfaces.Deletable;
import de.thm.mni.mote.mode.uiactor.shape.Line;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.input.InputEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.Collections;
import java.util.List;

/**
 * Created by hobbypunk on 16.02.17.
 */
public class ModifyableMoIconConnectorGroup extends MoIconGroup implements Actionable, Addable, Deletable {
  
  private static ConnectionBuilder builder = null;
  private static ChangeListener<Boolean> changeListener = null;
  private ModifyableMoIconConnectorGroup freezeTarget = null;
  
  public ModifyableMoIconConnectorGroup(MoDiagramGroup moParent, MoVariable variable, Boolean iconOnly) {
    super(moParent, variable.getType(), variable, iconOnly);
  }
  
  @Override
  public Command action(StateMachine sm, InputEvent inputEvent) {
    if (!(inputEvent instanceof MouseEvent)) return null;
    
    MouseEvent event = (MouseEvent) inputEvent;
    Point2D p = getMoParent().convertScenePointToDiagramPoint(new Point2D(event.getSceneX(), event.getSceneY()));
    
    if (event.getEventType().equals(MouseEvent.MOUSE_MOVED)) {
      builder.updateLastPoint(p);
      return Command.IGNORE;
    }
    if (event.getEventType().equals(MouseEvent.MOUSE_CLICKED) && event.getButton().equals(MouseButton.SECONDARY)) {
      abort(sm);
      return Command.IGNORE;
    }
    
    if (event.getEventType().equals(MouseEvent.MOUSE_CLICKED)) {
      if (builder == null) {
        builder = new ConnectionBuilder(this.getMoParent(), this.getVariables(), p);
        changeListener = (observable, oldValue, newValue) -> {
          if (!newValue) {
            abort(sm);
          }
        };
        sm.active.addListener(changeListener);
        sm.freeze();
        freezeTarget = this;
      } else {
        if (freezeTarget == this) {
          builder.addPoint();
        } else {
          try {
            builder.addPoint();
            MoConnection conn = builder.build(this.getVariables());
            return this.getMoParent().getModifyableMoClass().add((Object) new MoConnection[]{conn});
          } catch (ParserException e) {
            return Command.IGNORE;
          } finally {
            abort(sm);
          }
        }
      }
    }
    return Command.IGNORE;
  }
  
  private void abort(StateMachine sm) {
    builder.abort();
    sm.active.removeListener(changeListener);
    builder = null;
    changeListener = null;
    this.freezeTarget = null;
    sm.unfreeze();
    sm.switchToNone();
  }
  
  @Override
  public Command add(InputEvent event) {
    return null;
  }
  
  @Override
  public Command delete(InputEvent event) {
    MoConnection[] connections = getVariable().getConnections(getMoParent().getMoClass()).toArray(new MoConnection[]{});
    return this.getMoParent().getModifyableMoClass().delete((Object) connections);
  }
  
  private class ConnectionBuilder {
    private final Line line;
    private List<MoVariable> startConnector;
    private MoDiagramGroup moGroup;
    private ObjectProperty<Point2D> lastPoint = new SimpleObjectProperty<>(null);
    
    ConnectionBuilder(MoDiagramGroup moGroup, List<MoVariable> connector, Point2D start) {
      this.moGroup = moGroup;
      this.startConnector = connector;
      line = new Line(moGroup, new MoLine(start));
      moGroup.add(line);
      lastPoint.addListener((observable, oldValue, newValue) -> {
        if (oldValue != null) line.getData().getPoints().remove(oldValue);
        if (newValue != null) line.getData().getPoints().add(newValue);
      });
    }
    
    void updateLastPoint(Point2D point) {
      lastPoint.set(point);
    }
    
    void addPoint() {
      Point2D p = lastPoint.get();
      lastPoint.set(null);
      line.getData().getPoints().add(p);
    }
    
    void abort() {
      moGroup.remove(line);
    }
    
    MoConnection build(List<MoVariable> endConnector) throws ParserException {
      return new MoConnection(moGroup.getMoClass(), startConnector, endConnector, Collections.singletonList(line.getData()));
    }
  }
}