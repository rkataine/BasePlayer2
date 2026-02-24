package org.baseplayer.components.sidebars;

import org.baseplayer.components.MasterTrackCanvas;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

/**
 * Sidebar for sample/alignment tracks — extends {@link SidebarBase}.
 *
 * <p>The default header is replaced with a {@link MasterTrackCanvas} (which
 * extends {@link org.baseplayer.draw.GenomicCanvas} so that pan/zoom navigation
 * works in the header area).  The content area hosts a {@link SampleListPanel}.</p>
 */
public class SampleSidebar extends SidebarBase {

  public final MasterTrackCanvas masterTrack;
  public final SampleListPanel   sampleList;

  public SampleSidebar(StackPane parent) {
    super(parent, SampleRegistry.DEFAULT_MASTER_TRACK_HEIGHT);

    SampleRegistry sr = ServiceRegistry.getInstance().getSampleRegistry();

    // Rebind header height to the dynamic property
    headerPane.minHeightProperty().bind(sr.masterTrackHeightProperty());
    headerPane.maxHeightProperty().bind(sr.masterTrackHeightProperty());

    // Replace default header canvases with MasterTrackCanvas
    replaceHeaderContent();
    Canvas reactiveCanvas = new Canvas();
    masterTrack = new MasterTrackCanvas(reactiveCanvas, headerPane, new DrawStack());
    headerPane.getChildren().addAll(masterTrack, reactiveCanvas);

    // Sample list in content pane
    sampleList = new SampleListPanel(contentPane);
  }

  // ── SidebarBase contract ──────────────────────────────────────────────────

  @Override protected String getTitle() { return "Tracks"; }

  @Override protected int getItemCount() {
    return ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().size();
  }

  @Override protected void onSettingsClicked(double screenX, double screenY) {
    // Handled by MasterTrackCanvas directly
  }

  @Override protected void onAddClicked(double screenX, double screenY) {
    // Handled by MasterTrackCanvas directly
  }

  @Override protected void drawContent() {
    sampleList.draw();
  }

  /** Full repaint — header (MasterTrackCanvas) + content (SampleListPanel). */
  @Override
  public void draw() {
    masterTrack.draw();
    sampleList.draw();
  }
}
