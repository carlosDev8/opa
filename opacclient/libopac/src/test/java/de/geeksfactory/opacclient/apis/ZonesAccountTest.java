package de.geeksfactory.opacclient.apis;

import org.jsoup.Jsoup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.geeksfactory.opacclient.objects.LentItem;
import de.geeksfactory.opacclient.objects.ReservedItem;

import static org.junit.Assert.assertNotNull;

@RunWith(Parameterized.class)
public class ZonesAccountTest extends BaseAccountTest {
    private String file;

    public ZonesAccountTest(String file) {
        this.file = file;
    }

    private static final String[] FILES = new String[]{"koeln.html"};

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
        String html = readResource("/zones/medialist/" + file);
        if (html == null) return; // we may not have all files for all libraries
        List<LentItem> media = Zones.parseMediaList(Jsoup.parse(html));
        for (LentItem item : media) {
            assertNotNull(item.getTitle());
            assertNotNull(item.getDeadline());
        }
    }

    @Test
    public void testParseResList() throws OpacApi.OpacErrorException {
        String html = readResource("/zones/reslist/" + file);
        if (html == null) return; // we may not have all files for all libraries
        List<ReservedItem> media = Zones.parseResList(Jsoup.parse(html));
    }
}
