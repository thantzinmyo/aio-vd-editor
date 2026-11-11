import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
@OptIn(markerClass = UnstableApi.class)
public class ScratchTest {
    public void test(MediaSource s1, MediaSource s2) {
        new MergingMediaSource(true, true, s1, s2);
    }
}
