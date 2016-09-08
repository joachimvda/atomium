package be.wegenenverkeer.atomium.server.spring;

import be.vlaanderen.awv.dc.atomium.AtomEntry;
import be.vlaanderen.awv.dc.atomium.Feed;
import be.vlaanderen.awv.dc.atomium.TestFeedEntry;
import be.vlaanderen.awv.dc.atomium.TestFeedEntryTo;
import be.wegenenverkeer.atomium.japi.format.Link;
import be.wegenenverkeer.common.resteasy.exception.NotFoundException;
import be.wegenenverkeer.common.resteasy.json.RestJsonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import static java.util.Collections.emptyList;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class AtomiumServiceHelperTest {

    private static final int TEST_PAGE_SIZE = 2;
    private static final LocalDateTime NOW = LocalDateTime.now();
    public static final String TEST_FEED_NAME = "Test feed";
    public static final String TEST_FEED_URL = "/test/feed/url";

    @Spy
    private RestJsonMapper mapper;

    @InjectMocks
    private AtomiumServiceHelper helper;

    @Mock
    Request request;

    @Rule
    public ExpectedException exception = ExpectedException.none();
    private List<TestFeedEntry> onvolledigeLijst = maakTestFeedEntries(TEST_PAGE_SIZE);
    private List<TestFeedEntry> volledigeLijst = maakTestFeedEntries(TEST_PAGE_SIZE + 1);

    @Test
    public void sync() throws Exception {
        TestFeedProvider testFeedProvider = mock(TestFeedProvider.class);

        helper.sync(testFeedProvider);

        verify(testFeedProvider, times(1)).sync();
    }

    @Test
    public void geenEntriesVoorPageGevonden() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(emptyList());

        exception.expect(NotFoundException.class);
        exception.expectMessage("Pagina 0 niet gevonden.");

        helper.getFeed(testFeedProvider, 0, request, true);
    }

    @Test
    public void etag_isDeHashVanDeTimestampVanDeEersteEntry() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(volledigeLijst);

        Response response = helper.getFeed(testFeedProvider, 0, request, true);

        assertThat(response.getMetadata().getFirst("ETag")).isNotNull().isInstanceOf(EntityTag.class);

        String hashcodeVanVoorlaatsteItem = Integer.toString(volledigeLijst.get(0).getTimestamp().hashCode());

        assertThat(response.getMetadata().getFirst("ETag")).isEqualTo(EntityTag.valueOf(hashcodeVanVoorlaatsteItem));
    }

    @Test
    public void etag_geenMatch() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(volledigeLijst);
        String hashcodeVanVoorlaatsteItem = Integer.toString(volledigeLijst.get(TEST_PAGE_SIZE - 1).getTimestamp().hashCode());
        EntityTag etag = new EntityTag(hashcodeVanVoorlaatsteItem);

        // wanneer de etag niet matcht wordt de feed opnieuw opgebouwd
        when(request.evaluatePreconditions(etag)).thenReturn(null);

        Response actualResponse = helper.getFeed(testFeedProvider, 0, request, true);

        assertThat(actualResponse.getStatus()).isEqualTo(SC_OK); // echte response
        verify(mapper, times(1)).writeValueAsString(any()); // nieuwe feed schrijven
    }

    @Test
    public void etag_match() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(volledigeLijst);
        String hashcodeVanVoorlaatsteItem = Integer.toString(volledigeLijst.get(0).getTimestamp().hashCode());
        EntityTag etag = new EntityTag(hashcodeVanVoorlaatsteItem);

        Response expectedResponse = mock(Response.class);

        // wanneer de etag matcht wordt deze response builder gebruikt
        Response.ResponseBuilder responseBuilder = mock(Response.ResponseBuilder.class);
        when(responseBuilder.cacheControl(any())).thenReturn(responseBuilder);
        when(responseBuilder.tag(etag)).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(expectedResponse);
        when(request.evaluatePreconditions(etag)).thenReturn(responseBuilder);

        Response actualResponse = helper.getFeed(testFeedProvider, 0, request, true);

        assertThat(actualResponse).isEqualTo(expectedResponse);
        verify(responseBuilder, times(1)).build();
        verify(mapper, never()).writeValueAsString(any()); // geen nieuwe feed schrijven
    }

    @Test
    public void indienCurrentPage_nietGecached() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(onvolledigeLijst);

        Response response = helper.getFeed(testFeedProvider, 0, request, true);

        assertThat(response.getMetadata().getFirst("Cache-Control")).isNull();
    }

    @Test
    public void indienRecentPageNietVolledig_nietGecached() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(onvolledigeLijst);

        Response response = helper.getFeed(testFeedProvider, 0, request, false);

        assertThat(response.getMetadata().getFirst("Cache-Control")).isNull();
    }

    @Test
    public void indienRecentPageWelVolledig_welGecached() throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(volledigeLijst);

        Response response = helper.getFeed(testFeedProvider, 0, request, false);

        assertThat(response.getMetadata().getFirst("Cache-Control")).isNotNull();
        assertThat(response.getMetadata().getFirst("Cache-Control").toString())
                .isEqualTo("no-transform, max-age=0"); // max-age=0, want @Value wordt niet geïnitialiseerd in unit tests
    }

    @Test
    public void feedWordtOpgebouwd_metadata() throws Exception {
        Feed<TestFeedEntryTo> feed = getFeed(volledigeLijst);

        assertThat(feed.getId()).isEqualTo(TEST_FEED_NAME);
        assertThat(feed.getBase()).isEqualTo(TEST_FEED_URL);
        assertThat(feed.getTitle()).isEqualTo(TEST_FEED_NAME);
        assertThat(feed.getGenerator().getText()).isEqualTo("DistrictCenter");
        assertThat(feed.getGenerator().getUri()).isEqualTo(TEST_FEED_URL);
        assertThat(feed.getGenerator().getVersion()).isEqualTo("1.0");
    }

    @Test
    public void feedWordtOpgebouwd_onvolledigePage() throws Exception {
        List<TestFeedEntry> teTestenLijst = new ArrayList<>(onvolledigeLijst);
        int teTestenLijstSize = teTestenLijst.size();

        Feed<TestFeedEntryTo> feed = getFeed(teTestenLijst);

        String[] expectedIds = teTestenLijst.stream()
                .map(entry -> "urn:id:" + entry.getId())
                .toArray(String[]::new);

        assertThat(feed.getEntries()).hasSize(teTestenLijstSize).extracting("id").containsExactly(expectedIds);
    }

    @Test
    public void feedWordtOpgebouwd_volledigePage() throws Exception {
        // de code gaat er van uit dat je een page + 1 geeft, dat is een efficiente manier om te weten of de page volledig is
        // als je immers page size + 1 items krijgt weet je dat er nog elementen volgen
        List<TestFeedEntry> teTestenLijst = new ArrayList<>(volledigeLijst);
        int teTestenLijstSize = teTestenLijst.size();

        Feed<TestFeedEntryTo> feed = getFeed(teTestenLijst);

        String[] expectedIds = teTestenLijst.subList(1, teTestenLijst.size())
                .stream()
                .map(entry -> "urn:id:" + entry.getId())
                .toArray(String[]::new);

        assertThat(feed.getEntries()).hasSize(teTestenLijstSize - 1).extracting("id").containsExactly(expectedIds);
    }

    @Test
    public void feedWordtOpgebouwd_volledigePageIsVolledigeLijstMinDeLaatste() throws Exception {
        // de code gaat er van uit dat je een page + 1 geeft, dat is een efficiente manier om te weten of de page volledig is
        // als je immers page size + 1 items krijgt weet je dat er nog elementen volgen
        Feed<TestFeedEntryTo> feed = getFeed(volledigeLijst);

        List<TestFeedEntry> lijstClone = new ArrayList<>(volledigeLijst);
        String[] expectedIds = lijstClone.subList(1, lijstClone.size()).stream()
                .map(entry -> "urn:id:" + entry.getId())
                .toArray(String[]::new);

        assertThat(feed.getEntries()).hasSize(volledigeLijst.size() - 1).extracting("id").containsExactly(expectedIds);
    }

    @Test
    public void feedWordtOpgebouwd_linksVoorEenVolledigePage0() throws Exception {
        Feed<TestFeedEntryTo> feed = getFeed(volledigeLijst);

        assertThat(feed.getLinks()).isNotEmpty();
        assertThat(feed.getLinks()).extracting("rel").containsExactly("last", "previous", "self");

        assertThat(getLink(feed.getLinks(), "self").getHref()).isEqualTo("/0/2");
        assertThat(getLink(feed.getLinks(), "last").getHref()).isEqualTo("/0/2");
        assertThat(getLink(feed.getLinks(), "previous").getHref()).isEqualTo("/1/2");  // previous is de volgende pagina :-/
    }


    @Test
    public void feedWordtOpgebouwd_linksVoorEenVolledigePage1() throws Exception {
        Feed<TestFeedEntryTo> feed = getFeed(volledigeLijst, 1);

        assertThat(feed.getLinks()).isNotEmpty();
        assertThat(feed.getLinks()).extracting("rel").containsExactly("last", "next", "previous", "self");

        assertThat(getLink(feed.getLinks(), "self").getHref()).isEqualTo("/1/2");
        assertThat(getLink(feed.getLinks(), "last").getHref()).isEqualTo("/0/2");
        assertThat(getLink(feed.getLinks(), "next").getHref()).isEqualTo("/0/2"); // next is de vorige pagina :-/
        assertThat(getLink(feed.getLinks(), "previous").getHref()).isEqualTo("/2/2");  // previous is de volgende pagina :-/
    }

    @Test
    public void feedWordtOpgebouwd_linksVoorEenOnvolledigePage() throws Exception {
        Feed<TestFeedEntryTo> feed = getFeed(onvolledigeLijst);

        assertThat(feed.getLinks()).isNotEmpty();
        assertThat(feed.getLinks()).extracting("rel").containsExactly("last", "self");

        assertThat(getLink(feed.getLinks(), "self").getHref()).isEqualTo("/0/2");
        assertThat(getLink(feed.getLinks(), "last").getHref()).isEqualTo("/0/2");
    }

    @Test
    public void toAtomEntry() throws Exception {
        int id = 42;
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(onvolledigeLijst);
        AtomEntry<TestFeedEntryTo> atomEntry = helper.toAtomEntry(new TestFeedEntry(id, NOW), testFeedProvider);

        assertThat(atomEntry.getId()).isEqualTo("urn:id:" + id);
    }

    private Link getLink(List<Link> links, String name) {
        Optional<Link> link = links.stream().filter(current -> current.getRel().equals(name)).findFirst();
        assertThat(link).isPresent();
        return link.get();
    }

    private Feed<TestFeedEntryTo> getFeed(List<TestFeedEntry> lijst) throws Exception {
        return getFeed(lijst, 0);
    }

    private Feed<TestFeedEntryTo> getFeed(List<TestFeedEntry> lijst, int page) throws Exception {
        TestFeedProvider testFeedProvider = new TestFeedProvider();
        testFeedProvider.setEntriesForPage(lijst);

        Response response = helper.getFeed(testFeedProvider, page, request, false);
        return mapper.readValue(response.getEntity().toString(), new TypeReference<Feed<TestFeedEntryTo>>() {
        });
    }

    private List<TestFeedEntry> maakTestFeedEntries(int testPageSize) {
        return IntStream.rangeClosed(1, testPageSize)
                .mapToObj(integer -> new TestFeedEntry(testPageSize + 42 - integer, NOW.minusDays(integer)))
                .collect(Collectors.toList());
    }

    public class TestFeedProvider implements FeedProvider<TestFeedEntry, TestFeedEntryTo> {
        private List<TestFeedEntry> entries;

        @Override
        public List<TestFeedEntry> getEntriesForPage(long pageNumber) {
            return entries;
        }

        @Override
        public long totalNumberOfEntries() {
            return 42;
        }

        public void setEntriesForPage(List<TestFeedEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void sync() {
            // no-op
        }

        @Override
        public String getUrnForEntry(TestFeedEntry entry) {
            return URN_ID + entry.getId();
        }

        @Override
        public LocalDateTime getTimestampForEntry(TestFeedEntry entry) {
            return entry.getTimestamp();
        }

        @Override
        public TestFeedEntryTo toTo(TestFeedEntry entry) {
            return new TestFeedEntryTo(entry.getId());
        }

        @Override
        public String getFeedUrl() {
            return TEST_FEED_URL;
        }

        @Override
        public String getFeedName() {
            return TEST_FEED_NAME;
        }

        @Override
        public long getPageSize() {
            return TEST_PAGE_SIZE;
        }
    }
}