package de.thm.mni.mhpp11.util.parser;

import javafx.util.Pair;
import lombok.Getter;
import omc.corba.OMCClient;
import omc.corba.OMCInterface;
import omc.corba.Result;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by hobbypunk on 27.09.16.
 */
public class OMCompiler {
  
  private static OMCompiler ourInstance;
  
  public static OMCompiler getInstance(Path compiler, Path library, Locale locale) throws IOException {
    if (ourInstance == null) ourInstance = new OMCompiler(compiler, library, locale);
    return ourInstance;
  }
  
  public static OMCompiler getInstance() {
    return ourInstance;
  }
  
  
  public enum TYPE {
    TYPE,
    PACKAGE,
    CLASS,
    RECORD,
    FUNCTION,
    MODEL,
    CONNECTOR,
    ENUM,
    OPERATOR,
    OPERATOR_RECORD,
    NULL
  }
  
  private final Path library;
  private OMCInterface client;
  
  private Lock lock = new ReentrantLock();
  private Condition systemLibs;
  
  @Getter List<Pair<String, Path>> systemLibraries = new ArrayList<>();
  @Getter List<Pair<String, Path>> projectLibraries = new ArrayList<>();
  @Getter Pair<String, Path> project = null;
  
  private OMCompiler(Path compiler, Path library, Locale locale) throws IOException {
    this.library = library;
    client = new OMCClient(compiler.toString(), locale.toString());
    client.connect();
    this.preloadSystemLibraries();
  }
  
  private void preloadSystemLibraries() {
    Thread t = new Thread(() -> {
      lock.lock();
      systemLibs = lock.newCondition();
      for (String s : OMCompiler.this.getAvailableLibraries()) {
        sendExpression("loadModel(" + s + ")", true);
      }
      loadSystemLibraries();
      Condition s = systemLibs;
      systemLibs = null;
      s.signal();
      lock.unlock();
    });
    t.start();
  }
  
  public void setProject(Path f) throws ParserException {
    if (this.project != null) throw new ParserException("project already set");
    Pair<String, Path> lib = addLibrary(f);
    if (lib == null) throw new ParserException("project not loaded");
    this.project = lib;
  }
  
  private Pair<String, Path> addLibrary(Path f) throws ParserException {
    f = f.toAbsolutePath().normalize();
    loadSystemLibraries(); // if not loaded now;
    
    Result r = sendExpression(String.format("loadFile(\"%s\")", f));
    if (r.result.contains("false")) throw new ParserException("Cannot load file");
    if (r.error.isPresent()) throw new ParserException(r.error.get());
    
    r = sendExpression("getLoadedLibraries()");
    
    List<Pair<String, Path>> list = toLibraryArray(r.result);
    
    for (Pair<String, Path> entry : list) {
      if (entry.equals(project)) continue;
      if (systemLibraries.contains(entry) || projectLibraries.contains(entry)) continue;
      if (!f.startsWith(entry.getValue())) continue;
      return new Pair<>(entry.getKey(), f);
    }
    return null;
  }
  
  public void addProjectLibraries(List<Path> files) throws ParserException {
    for (Path f : files) {
      Pair<String, Path> p = addLibrary(f);
      if (p != null) this.projectLibraries.add(p);
    }
  }
  
  private List<String> getAvailableLibraries() {
    Result result = client.sendExpression("getAvailableLibraries()");
    return toStringArray(result.result);
  }
  
  private void loadSystemLibraries() {
    if (systemLibraries.isEmpty()) {
      Result result = sendExpression("getLoadedLibraries()", true);
      
      List<Pair<String, Path>> tmp = new ArrayList<>();
      List<Pair<String, Path>> list = toLibraryArray(result.result);
      for (Pair<String, Path> entry : list) {
        String name = entry.getKey().toLowerCase();
        if (name.contains("obsolete") || name.contains("test")) continue;
        if (this.library != null && entry.getValue().startsWith(this.library))
          tmp.add(entry);
      }
      systemLibraries.addAll(tmp);
    }
  }
  
  public String getDescription(String className) {
    Result result = sendExpression("getClassComment(" + className + ")");
    return result.result;
  }
  
  public List<String> getChildren(String className) {
    Result result = sendExpression("getClassNames(" + className + ")");
    return toStringArray(result.result);
  }
  
  public TYPE getType(String className) {
    Result r;
    
    r = sendExpression("isType(" + className + ")");
    if (r.result.contains("true")) return TYPE.TYPE;
    
    r = sendExpression("isPackage(" + className + ")");
    if (r.result.contains("true")) return TYPE.PACKAGE;
    
    r = sendExpression("isClass(" + className + ")");
    if (r.result.contains("true")) return TYPE.CLASS;
    
    r = sendExpression("isRecord(" + className + ")");
    if (r.result.contains("true")) return TYPE.RECORD;
    
    r = sendExpression("isFunction(" + className + ")");
    if (r.result.contains("true")) return TYPE.FUNCTION;
    
    r = sendExpression("isModel(" + className + ")");
    if (r.result.contains("true")) return TYPE.MODEL;
    
    r = sendExpression("isConnector(" + className + ")");
    if (r.result.contains("true")) return TYPE.CONNECTOR;
    
    r = sendExpression("isEnumeration(" + className + ")");
    if (r.result.contains("true")) return TYPE.ENUM;
  
    r = sendExpression("isOperator(" + className + ")");
    if (r.result.contains("true")) return TYPE.OPERATOR;
  
    r = sendExpression("isOperatorRecord(" + className + ")");
    if (r.result.contains("true")) return TYPE.OPERATOR_RECORD;
    return TYPE.NULL;
  }
  
  public String getDocumentation(String className) {
    Result result = sendExpression("getDocumentationAnnotation(" + className + ")");
    List<String> l = toStringArray(result.result, false);
    if (l.isEmpty()) return null;
    return l.get(0);
  }
  
  public String getIcon(String className) {
    Result result = sendExpression("getIconAnnotation(" + className + ")");
    return result.result.replaceAll("(^\\{)|(\\}$)", "");
  }
  
  public void disconnect() {
    waitLibs();
    try {
      client.disconnect();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  
  private List<String> toStringArray(String s) {
    return toStringArray(s, true);
  }
  
  private List<String> toStringArray(String s, Boolean sort) {
    List<String> l = Arrays.asList(s.split(","));
    
    for (int i = 0; i < l.size(); i++) {
      l.set(i, l.get(i).replaceAll("(^[\\{\\\"]*)|([\\\"\\}]*$)", ""));
    }
    
    if (sort) l.sort(String::compareToIgnoreCase);
    
    return l;
  }
  
  private List<Pair<String, Path>> toLibraryArray(String s) {
    List<String> tmp = toStringArray(s, false);
    List<Pair<String, Path>> list = new ArrayList<>();
    for (int i = 0; i < tmp.size(); i += 2) {
      list.add(new Pair<>(tmp.get(i), Paths.get(tmp.get(i + 1))));
    }
    return list;
  }
  
  public Result sendExpression(String s) {
    return sendExpression(s, false);
  }
  
  private Result sendExpression(String s, Boolean ignoreLoaded) {
    if (!ignoreLoaded)
      waitLibs();
    Result r = client.sendExpression(s);
    return r;
  }
  
  private void waitLibs() {
    lock.lock();
    try {
      if (systemLibs != null) {
        systemLibs.await();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    lock.unlock();
  }
}