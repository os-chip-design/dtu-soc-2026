import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object GeneratedRtl {
  private val BlackBoxFooterMarker =
    """// ----- 8< ----- FILE "firrtl_black_box_resource_files.f" ----- 8< -----"""

  def stripBlackBoxFooter(path: Path): Unit = {
    val contents = Files.readString(path, StandardCharsets.UTF_8)
    val cleaned = contents.split(BlackBoxFooterMarker, 2).head.trim + "\n"
    Files.writeString(path, cleaned, StandardCharsets.UTF_8)
  }
}
