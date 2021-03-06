package de.thm.mni.mote.mode.frontend.editor.actionmanager.commands;

import de.thm.mni.mote.mode.frontend.editor.actionmanager.interfaces.Deletable;

/**
 * Created by Marcel Hoppe on 21.02.17.
 */
public class DeleteCommand extends Command {
  
  private final Deletable that;
  
  public DeleteCommand(Deletable that, Object... params) {
    super(params);
    this.that = that;
  }
  
  @Override
  public Command execute() {
    return that.delete(params);
  }
}
