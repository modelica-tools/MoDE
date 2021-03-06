package de.thm.mni.mote.mode.frontend.shape;

import de.thm.mni.mote.mode.config.Settings;
import de.thm.mni.mote.mode.modelica.graphics.MoLine;
import de.thm.mni.mote.mode.frontend.controls.modelica.FXMoParentGroup;
import javafx.scene.paint.Color;

@SuppressWarnings("WeakerAccess")
public class LineWithHelper extends Line {
  
  private final InternalLine lineHelper;
  
  protected LineWithHelper(FXMoParentGroup parent, MoLine data) {
    super(parent, data);
    this.lineHelper = new InternalLine(data, false);
    init();
  }
  
  @Override
  public void init() {
    super.init();
    this.lineHelper.setStroke(Color.TRANSPARENT);
    this.lineHelper.strokeWidthProperty().bind(this.getLine().strokeWidthProperty().add(Settings.load().getMainwindow().getEditor().getLineClickRadius()));
  
    this.getChildren().add(this.lineHelper);
  }
}
