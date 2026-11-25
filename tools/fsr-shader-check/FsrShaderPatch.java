import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import java.nio.file.*;
import java.util.Map;
public class FsrShaderPatch {
  public static void main(String[] a) throws Exception {
    String base = "/workspace/Iris/common/src/main/resources/fsr3fg/";
    String v = Files.readString(Path.of(base+"fullscreen.vsh"));
    for (String f : new String[]{"motion.fsh","interpolate.fsh","fi_pyramid.fsh","fi_inpaint.fsh","fsr2_accumulate.fsh","fsr2_rcas.fsh","fi_clear.csh","fi_scatter.csh","fi_resolve.csh"}) {
      String src = Files.readString(Path.of(base+f));
      Map<PatchShaderType,String> m = f.endsWith(".csh") ? Map.of(PatchShaderType.COMPUTE, src) : Map.of(PatchShaderType.VERTEX, v, PatchShaderType.FRAGMENT, src);
      var layout = VulkanShaderPatcher.discover(m).layout();
      var u = new DrawUniforms(layout);
      for (boolean vk : new boolean[]{false, true}) {
        var r = VulkanShaderPatcher.patch(m, null, null, n -> true, u.plan(), vk, Int2IntMaps.EMPTY_MAP);
        String dir = vk ? "vk/" : "gl/";
        Files.createDirectories(Path.of(dir));
        r.sources().forEach((k, s) -> { try { Files.writeString(Path.of(dir + f + "." + (k==PatchShaderType.VERTEX?"vert":k==PatchShaderType.COMPUTE?"comp":"frag")), s);} catch(Exception e){throw new RuntimeException(e);} });
      }
      System.out.println("patched " + f);
    }
  }
}
