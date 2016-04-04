package de.geeksfactory.opacclient.apis;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.geeksfactory.opacclient.i18n.DummyStringProvider;
import de.geeksfactory.opacclient.objects.LentItem;
import de.geeksfactory.opacclient.objects.ReservedItem;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class WinBiapAccountTest extends BaseAccountTest {
    private String file;

    public WinBiapAccountTest(String file) {
        this.file = file;
    }

    private static final String[] FILES =
            new String[]{"guetersloh.html", "geltendorf.html", "neufahrn.html", "memmingen.html"};

    @Parameterized.Parameters(name = "{0}")
    public static Collection<String[]> files() {
        List<String[]> files = new ArrayList<>();
        for (String file : FILES) {
            files.add(new String[]{file});
        }
        return files;
    }

    @Test
    public void testParseMediaList() throws OpacApi.OpacErrorException {
        String html = readResource("/winbiap/medialist/" + file);
        if (html == null) return; // we may not have all files for all libraries
        List<LentItem> media = WinBiap.parseMediaList(Jsoup.parse(html));
        assertTrue(media.size() > 0);
        for (LentItem item : media) {
            assertNotNull(item.getTitle());
            assertNotNull(item.getDeadline());
        }
    }

    @Test
    public void testParseResList() throws OpacApi.OpacErrorException {
        String html = readResource("/winbiap/reslist/" + file);
        if (html == null) return; // we may not have all files for all libraries
        List<ReservedItem> media =
                WinBiap.parseResList(Jsoup.parse(html), new DummyStringProvider(),
                        new JSONObject());
        assertTrue(media.size() > 0);
    }
}
