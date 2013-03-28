package de.geeksfactory.opacclient.apis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.acra.ACRA;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpProtocolParams;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import de.geeksfactory.opacclient.NotReachableException;
import de.geeksfactory.opacclient.apis.OpacApi.ReservationResult.Status;
import de.geeksfactory.opacclient.objects.Account;
import de.geeksfactory.opacclient.objects.AccountData;
import de.geeksfactory.opacclient.objects.Detail;
import de.geeksfactory.opacclient.objects.DetailledItem;
import de.geeksfactory.opacclient.objects.Filter;
import de.geeksfactory.opacclient.objects.Filter.Option;
import de.geeksfactory.opacclient.objects.Library;
import de.geeksfactory.opacclient.objects.SearchRequestResult;
import de.geeksfactory.opacclient.objects.SearchResult;
import de.geeksfactory.opacclient.objects.SearchResult.MediaType;
import de.geeksfactory.opacclient.storage.MetaDataSource;

/**
 * OpacApi implementation for Web Opacs of the SISIS SunRise product, developed
 * by OCLC.
 * 
 * Restrictions: Bookmarks are only constantly supported if the library uses the
 * BibTip extension.
 */
public class SISIS implements OpacApi {
	private String opac_url = "";
	private JSONObject data;
	private DefaultHttpClient ahc;
	private MetaDataSource metadata;
	private boolean initialised = false;
	private String last_error;
	private Library library;

	private String CSId;
	private String identifier;
	private String reusehtml;
	private int resultcount = 10;

	private long logged_in;
	private Account logged_in_as;
	private final long SESSION_LIFETIME = 1000 * 60 * 3;

	private static HashMap<String, MediaType> defaulttypes = new HashMap<String, MediaType>();

	static {
		defaulttypes.put("g", MediaType.EBOOK);
		defaulttypes.put("d", MediaType.CD);
		defaulttypes.put("0", MediaType.BOOK);
		defaulttypes.put("2", MediaType.BOOK);
		defaulttypes.put("7", MediaType.CD_MUSIC);
		defaulttypes.put("8", MediaType.CD_MUSIC);
		defaulttypes.put("12", MediaType.AUDIO_CASSETTE);
		defaulttypes.put("13", MediaType.CD);
		defaulttypes.put("15", MediaType.DVD);
		defaulttypes.put("16", MediaType.CD);
		defaulttypes.put("17", MediaType.MOVIE);
		defaulttypes.put("18", MediaType.MOVIE);
		defaulttypes.put("19", MediaType.MOVIE);
		defaulttypes.put("20", MediaType.DVD);
		defaulttypes.put("21", MediaType.SCORE_MUSIC);
		defaulttypes.put("22", MediaType.BOARDGAME);
		defaulttypes.put("26", MediaType.CD);
		defaulttypes.put("27", MediaType.CD);
		defaulttypes.put("37", MediaType.CD);
		defaulttypes.put("29", MediaType.AUDIOBOOK);
		defaulttypes.put("46", MediaType.GAME_CONSOLE_NINTENDO);
		defaulttypes.put("56", MediaType.EBOOK);
		defaulttypes.put("96", MediaType.EBOOK);
		defaulttypes.put("97", MediaType.EBOOK);
		defaulttypes.put("99", MediaType.EBOOK);
		defaulttypes.put("EB", MediaType.EBOOK);
		defaulttypes.put("buch01", MediaType.BOOK);
		defaulttypes.put("buch02", MediaType.PACKAGE_BOOKS);
		defaulttypes.put("buch03", MediaType.BOOK);
		defaulttypes.put("buch04", MediaType.PACKAGE_BOOKS);
		defaulttypes.put("buch05", MediaType.PACKAGE_BOOKS);
	}

	@Override
	public String[] getSearchFields() {
		return new String[] { KEY_SEARCH_QUERY_FREE, KEY_SEARCH_QUERY_TITLE,
				KEY_SEARCH_QUERY_AUTHOR, KEY_SEARCH_QUERY_KEYWORDA,
				KEY_SEARCH_QUERY_KEYWORDB, KEY_SEARCH_QUERY_HOME_BRANCH,
				KEY_SEARCH_QUERY_BRANCH, KEY_SEARCH_QUERY_ISBN,
				KEY_SEARCH_QUERY_YEAR, KEY_SEARCH_QUERY_SYSTEM,
				KEY_SEARCH_QUERY_AUDIENCE, KEY_SEARCH_QUERY_PUBLISHER };
	}

	@Override
	public String getLast_error() {
		return last_error;
	}

	protected String convertStreamToString(InputStream is) throws IOException {
		BufferedReader reader;
		try {
			reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
		} catch (UnsupportedEncodingException e1) {
			reader = new BufferedReader(new InputStreamReader(is));
		}
		StringBuilder sb = new StringBuilder();

		String line = null;
		try {
			while ((line = reader.readLine()) != null) {
				sb.append((line + "\n"));
			}
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return sb.toString();
	}

	public void extract_meta(Document doc) {
		// Zweigstellen auslesen
		Elements zst_opts = doc.select("#selectedSearchBranchlib option");
		metadata.open();
		metadata.clearMeta(library.getIdent());
		for (int i = 0; i < zst_opts.size(); i++) {
			Element opt = zst_opts.get(i);
			if (!opt.val().equals(""))
				metadata.addMeta(MetaDataSource.META_TYPE_BRANCH,
						library.getIdent(), opt.val(), opt.text());
		}

		zst_opts = doc.select("#selectedViewBranchlib option");
		for (int i = 0; i < zst_opts.size(); i++) {
			Element opt = zst_opts.get(i);
			if (!opt.val().equals(""))
				metadata.addMeta(MetaDataSource.META_TYPE_HOME_BRANCH,
						library.getIdent(), opt.val(), opt.text());
		}

		metadata.close();
	}

	@Override
	public void start() throws ClientProtocolException, SocketException,
			IOException, NotReachableException {

		// Some libraries require start parameters for start.do, like Login=foo
		String startparams = "";
		if (data.has("startparams")) {
			try {
				startparams = "?" + data.getString("startparams");
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		String html = httpGet(opac_url + "/start.do" + startparams);

		initialised = true;

		Document doc = Jsoup.parse(html);
		CSId = doc.select("input[name=CSId]").val();

		metadata.open();
		if (!metadata.hasMeta(library.getIdent())) {
			metadata.close();
			extract_meta(doc);
		} else {
			metadata.close();
		}
	}

	@Override
	public void init(MetaDataSource metadata, Library lib) {
		ahc = new DefaultHttpClient();
		HttpProtocolParams.setUserAgent(ahc.getParams(), "OpacApp.de");

		this.metadata = metadata;
		this.library = lib;
		this.data = lib.getData();

		try {
			this.opac_url = data.getString("baseurl");
		} catch (JSONException e) {
			ACRA.getErrorReporter().handleException(e);
		}
	}

	public static String getStringFromBundle(Bundle bundle, String key) {
		// Workaround for Bundle.getString(key, default) being available not
		// before API 12
		String res = bundle.getString(key);
		if (res == null)
			res = "";
		return res;
	}

	protected int addParameters(Bundle query, String key, String searchkey,
			List<NameValuePair> params, int index) {
		if (!query.containsKey(key) || query.getString(key).equals(""))
			return index;

		if (index != 0)
			params.add(new BasicNameValuePair("combinationOperator[" + index
					+ "]", "AND"));
		params.add(new BasicNameValuePair("searchCategories[" + index + "]",
				searchkey));
		params.add(new BasicNameValuePair("searchString[" + index + "]", query
				.getString(key)));
		return index + 1;

	}

	@Override
	public SearchRequestResult search(Bundle query) throws IOException,
			NotReachableException {
		List<NameValuePair> params = new ArrayList<NameValuePair>();

		if (query.containsKey("volume")) {
			params.add(new BasicNameValuePair("methodToCall", "volumeSearch"));
			params.add(new BasicNameValuePair("dbIdentifier", query
					.getString("dbIdentifier")));
			params.add(new BasicNameValuePair("catKey", query
					.getString("catKey")));
			params.add(new BasicNameValuePair("periodical", "N"));
		} else {
			int index = 0;
			start();

			params.add(new BasicNameValuePair("CSId", CSId));
			params.add(new BasicNameValuePair("methodToCall", "submit"));
			params.add(new BasicNameValuePair("methodToCallParameter",
					"submitSearch"));
			params.add(new BasicNameValuePair("callingPage", "searchParameters"));
			params.add(new BasicNameValuePair("submitSearch", "Suchen"));

			index = addParameters(query, KEY_SEARCH_QUERY_FREE, "-1", params,
					index);
			index = addParameters(query, KEY_SEARCH_QUERY_TITLE, "331", params,
					index);
			index = addParameters(query, KEY_SEARCH_QUERY_AUTHOR, "100",
					params, index);
			index = addParameters(query, KEY_SEARCH_QUERY_ISBN, "540", params,
					index);
			index = addParameters(query, KEY_SEARCH_QUERY_KEYWORDA, "902",
					params, index);
			index = addParameters(query, KEY_SEARCH_QUERY_KEYWORDB, "710",
					params, index);
			index = addParameters(query, KEY_SEARCH_QUERY_YEAR, "425", params,
					index);
			index = addParameters(query, KEY_SEARCH_QUERY_PUBLISHER, "412",
					params, index);
			index = addParameters(query, KEY_SEARCH_QUERY_SYSTEM, "700",
					params, index);
			index = addParameters(query, KEY_SEARCH_QUERY_AUDIENCE, "1001",
					params, index);

			if (index == 0) {
				last_error = "Es wurden keine Suchkriterien eingegeben.";
				return null;
			}
			if (index > 4) {
				last_error = "Diese Bibliothek unterstützt nur bis zu vier benutzte Suchkriterien.";
				return null;
			}

			params.add(new BasicNameValuePair("selectedSearchBranchlib", query
					.getString(KEY_SEARCH_QUERY_BRANCH)));
			if (query.getString(KEY_SEARCH_QUERY_HOME_BRANCH) != null) {
				if (!query.getString(KEY_SEARCH_QUERY_HOME_BRANCH).equals(""))
					params.add(new BasicNameValuePair("selectedViewBranchlib",
							query.getString(KEY_SEARCH_QUERY_HOME_BRANCH)));
			}
		}

		String html = httpGet(opac_url + "/search.do?"
				+ URLEncodedUtils.format(params, "UTF-8"));
		return parse_search(html, 1);
	}

	@Override
	public SearchRequestResult searchGetPage(int page) throws IOException,
			NotReachableException {
		if (!initialised)
			start();

		String html = httpGet(opac_url
				+ "/hitList.do?methodToCall=pos&identifier=" + identifier
				+ "&curPos=" + (((page - 1) * resultcount) + 1));
		return parse_search(html, page);
	}

	protected SearchRequestResult parse_search(String html, int page) {
		Document doc = Jsoup.parse(html);

		if (doc.select(".error").size() > 0) {
			last_error = doc.select(".error").text().trim();
			return null;
		} else if (doc.select(".nohits").size() > 0) {
			last_error = doc.select(".nohits").text().trim();
			return null;
		}

		int results_total = -1;

		String resultnumstr = doc.select(".box-header h2").first().text();
		if (resultnumstr.contains("(1/1)") || resultnumstr.contains(" 1/1")) {
			reusehtml = html;
			last_error = "is_a_redirect";
			return null;
		} else if (resultnumstr.contains("(")) {
			results_total = Integer.parseInt(resultnumstr.replaceAll(
					".*\\(([0-9]+)\\).*", "$1"));
		}

		Elements table = doc.select("table.data tbody tr");
		identifier = null;
		List<SearchResult> results = new ArrayList<SearchResult>();
		for (int i = 0; i < table.size(); i++) {
			Element tr = table.get(i);
			SearchResult sr = new SearchResult();
			if (tr.select("td img").size() > 0) {
				String[] fparts = tr.select("td img").get(0).attr("src")
						.split("/");
				String fname = fparts[fparts.length - 1];
				if (data.has("mediatypes")) {
					try {
						sr.setType(MediaType.valueOf(data.getJSONObject(
								"mediatypes").getString(fname)));
					} catch (JSONException e) {
						sr.setType(defaulttypes.get(fname.toLowerCase()
								.replace(".jpg", "").replace(".gif", "")
								.replace(".png", "")));
					} catch (IllegalArgumentException e) {
						sr.setType(defaulttypes.get(fname.toLowerCase()
								.replace(".jpg", "").replace(".gif", "")
								.replace(".png", "")));
					}
				} else {
					sr.setType(defaulttypes.get(fname.toLowerCase()
							.replace(".jpg", "").replace(".gif", "")
							.replace(".png", "")));
				}
			}

			List<Node> children = tr.child(2).childNodes();
			if (tr.child(2).select("div").size() == 1) {
				children = tr.child(2).select("div").first().childNodes();
			} else if (tr.child(2).select("span.titleData").size() == 1) {
				children = tr.child(2).select("span.titleData").first()
						.childNodes();
			}
			int childrennum = children.size();
			boolean haslink = false;

			List<String[]> strings = new ArrayList<String[]>();
			for (int ch = 0; ch < childrennum; ch++) {
				Node node = children.get(ch);
				if (node instanceof TextNode) {
					String text = ((TextNode) node).text().trim();
					if (text.length() > 3)
						strings.add(new String[] { "text", "", text });
				} else if (node instanceof Element) {
					if (node.hasAttr("href") && !haslink) {
						haslink = true;
						try {
							List<NameValuePair> anyurl = URLEncodedUtils.parse(
									new URI(((Element) node).attr("href")),
									"UTF-8");
							for (NameValuePair nv : anyurl) {
								if (nv.getName().equals("identifier")) {
									identifier = nv.getValue();
									break;
								}
							}
						} catch (Exception e) {
							e.printStackTrace();
						}

					}

					List<Node> subchildren = node.childNodes();
					for (int j = 0; j < subchildren.size(); j++) {
						Node subnode = subchildren.get(j);
						if (subnode instanceof TextNode) {
							String text = ((TextNode) subnode).text().trim();
							if (text.length() > 3)
								strings.add(new String[] {
										((Element) node).tag().getName(),
										"text", text,
										((Element) node).className(),
										((Element) node).attr("style") });
						} else if (subnode instanceof Element) {
							String text = ((Element) subnode).text().trim();
							if (text.length() > 3)
								strings.add(new String[] {
										((Element) node).tag().getName(),
										((Element) subnode).tag().getName(),
										text, ((Element) node).className(),
										((Element) node).attr("style") });
						}
					}
				}
			}

			StringBuilder description = null;
			if (tr.child(2).select("span.Z3988").size() == 1) {
				// Sometimes there is a <span class="Z3988"> item which provides
				// data in a standardized format.
				List<NameValuePair> z3988data;
				try {
					description = new StringBuilder();
					z3988data = URLEncodedUtils.parse(new URI("http://dummy/?"
							+ tr.child(2).select("span.Z3988").attr("title")),
							"UTF-8");
					for (NameValuePair nv : z3988data) {
						if (nv.getValue() != null) {
							if (!nv.getValue().trim().equals("")) {
								if (nv.getName().equals("rft.btitle")) {
									description.append("<b>" + nv.getValue()
											+ "</b>");
								} else if (nv.getName().equals("rft.au")) {
									description
											.append("<br />" + nv.getValue());
								} else if (nv.getName().equals("rft.date")) {
									description
											.append("<br />" + nv.getValue());
								}
							}
						}
					}
				} catch (URISyntaxException e) {
					description = null;
				}
			}
			boolean described = false;
			if (description != null && description.length() > 0) {
				sr.setInnerhtml(description.toString());
				described = true;
			} else {
				description = new StringBuilder();
			}
			int k = 0;
			boolean yearfound = false;
			for (String[] part : strings) {
				if (!described) {
					if (part[0] == "a" && k == 0) {
						description.append("<b>" + part[2] + "</b>");
					} else if (part[2].matches("\\D*[0-9]{4}\\D*")
							&& part[2].length() <= 10) {
						yearfound = true;
						description.append("<br />" + part[2]);
					} else if (k == 1 && !yearfound
							&& part[2].matches("^\\s*\\([0-9]{4}\\)$")) {
						description.append("<br />" + part[2]);
					} else if (k < 3 && !yearfound) {
						description.append("<br />" + part[2]);
					}
				}
				if (part.length == 4) {
					if (part[0].equals("span") && part[3].equals("textgruen")) {
						sr.setStatus(SearchResult.Status.GREEN);
					} else if (part[0].equals("span")
							&& part[3].equals("textrot")) {
						sr.setStatus(SearchResult.Status.RED);
					}
				} else if (part.length == 5) {
					if (part[4].contains("purple")) {
						sr.setStatus(SearchResult.Status.YELLOW);
					}
				}
				if (sr.getStatus() == null) {
					if (part[2].contains("entliehen")
							&& part[2]
									.startsWith("Vormerkung ist leider nicht möglich")) {
						sr.setStatus(SearchResult.Status.RED);
					} else if (part[2].startsWith("entliehen")
							|| part[2]
									.contains("Ein Exemplar finden Sie in einer anderen Zweigstelle")) {
						sr.setStatus(SearchResult.Status.YELLOW);
					} else if ((part[2].startsWith("bestellbar") && !part[2]
							.contains("nicht bestellbar"))
							|| (part[2].startsWith("vorbestellbar") && !part[2]
									.contains("nicht vorbestellbar"))
							|| (part[2].startsWith("vormerkbar") && !part[2]
									.contains("nicht vormerkbar"))
							|| (part[2].contains("ausleihbar") && !part[2]
									.contains("nicht ausleihbar"))) {
						sr.setStatus(SearchResult.Status.GREEN);
					}
				}
				k++;
			}
			if (!described)
				sr.setInnerhtml(description.toString());

			sr.setNr(i);
			sr.setId(null);
			results.add(sr);
		}
		resultcount = results.size();
		return new SearchRequestResult(results, results_total, page);
	}

	@Override
	public DetailledItem getResultById(String id, String homebranch)
			throws IOException, NotReachableException {

		if (id == null && reusehtml != null) {
			return parse_result(reusehtml);
		}

		// Some libraries require start parameters for start.do, like Login=foo
		String startparams = "";
		if (data.has("startparams")) {
			try {
				startparams = data.getString("startparams") + "&";
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		String hbp = "";
		if (homebranch != null)
			hbp = "&selectedViewBranchlib=" + homebranch;

		String html = httpGet(opac_url + "/start.do?" + startparams
				+ "searchType=1&Query=0%3D%22" + id + "%22" + hbp);

		return parse_result(html);
	}

	@Override
	public DetailledItem getResult(int nr) throws IOException {
		String html = httpGet(opac_url
				+ "/singleHit.do?tab=showExemplarActive&methodToCall=showHit&curPos="
				+ (nr + 1) + "&identifier=" + identifier);

		return parse_result(html);
	}

	protected DetailledItem parse_result(String html) throws IOException {
		Document doc = Jsoup.parse(html);

		String html2 = httpGet(opac_url
				+ "/singleHit.do?methodToCall=activateTab&tab=showTitleActive");

		Document doc2 = Jsoup.parse(html2);

		String html3 = httpGet(opac_url
				+ "/singleHit.do?methodToCall=activateTab&tab=showAvailabilityActive");

		Document doc3 = Jsoup.parse(html3);
		doc3.setBaseUri(opac_url);

		DetailledItem result = new DetailledItem();

		try {
			result.setId(doc.select("#bibtip_id").text().trim());
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		List<String> reservationlinks = new ArrayList<String>();
		for (Element link : doc3.select("#vormerkung a, #tab-content a")) {
			Uri href = Uri.parse(link.absUrl("href"));
			if (result.getId() == null) {
				// ID retrieval
				String key = href.getQueryParameter("katkey");
				if (key != null) {
					result.setId(key);
					break;
				}
			}

			// Vormerken
			if (href.getQueryParameter("methodToCall") != null) {
				if (href.getQueryParameter("methodToCall").equals(
						"doVormerkung")
						|| href.getQueryParameter("methodToCall").equals(
								"doBestellung"))
					reservationlinks.add(href.getQuery());
			}
		}
		if (reservationlinks.size() == 1) {
			result.setReservable(true);
			result.setReservation_info(reservationlinks.get(0));
		} else if (reservationlinks.size() == 0) {
			result.setReservable(false);
		} else {
			// TODO: Multiple options - handle this case!
		}

		if (doc.select(".data td img").size() == 1) {
			result.setCover(doc.select(".data td img").first().attr("abs:src"));
		}

		result.setTitle(doc.select(".data td strong").first().text());

		String title = "";
		String text = "";
		Element detailtrs = doc2.select("#tab-content .data td").first();
		for (Node node : detailtrs.childNodes()) {
			if (node instanceof Element) {
				if (((Element) node).tag().getName().equals("strong")) {
					if (!text.equals("") && !title.equals("")) {
						result.addDetail(new Detail(title.trim(), text.trim()));
						if (title.equals("Titel:")) {
							result.setTitle(text.trim());
						}
						text = "";
					}

					title = ((Element) node).text().trim();
				} else {
					text = text + ((Element) node).text();
				}
			} else if (node instanceof Element) {
				text = text + ((Element) node).text();
			} else if (node instanceof TextNode) {
				text = text + ((TextNode) node).text();
			}
		}
		if (!text.equals("") && !title.equals("")) {
			result.addDetail(new Detail(title.trim(), text.trim()));
			if (title.equals("Titel:")) {
				result.setTitle(text.trim());
			}
		}
		for (Element link : doc3.select("#tab-content a")) {
			Uri href = Uri.parse(link.absUrl("href"));
			if (result.getId() == null) {
				// ID retrieval
				String key = href.getQueryParameter("katkey");
				if (key != null) {
					result.setId(key);
					break;
				}
			}
		}

		Elements exemplartrs = doc.select("#tab-content .data tr:not(#bg2)");
		for (int i = 0; i < exemplartrs.size(); i++) {
			Element tr = exemplartrs.get(i);
			try {
				ContentValues e = new ContentValues();
				e.put(DetailledItem.KEY_COPY_BARCODE, tr.child(1).text().trim());
				e.put(DetailledItem.KEY_COPY_BRANCH, tr.child(3).text().trim());
				e.put(DetailledItem.KEY_COPY_STATUS, tr.child(4).text().trim());
				result.addCopy(e);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		try {
			Element isvolume = null;
			Bundle volume = new Bundle();
			Elements links = doc.select(".data td a");
			int elcount = links.size();
			for (int eli = 0; eli < elcount; eli++) {
				List<NameValuePair> anyurl = URLEncodedUtils.parse(new URI(
						links.get(eli).attr("href")), "UTF-8");
				for (NameValuePair nv : anyurl) {
					if (nv.getName().equals("methodToCall")
							&& nv.getValue().equals("volumeSearch")) {
						isvolume = links.get(eli);
					} else if (nv.getName().equals("catKey")) {
						volume.putString("catKey", nv.getValue());
					} else if (nv.getName().equals("dbIdentifier")) {
						volume.putString("dbIdentifier", nv.getValue());
					}
				}
				if (isvolume != null) {
					volume.putBoolean("volume", true);
					result.setVolumesearch(volume);
					break;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return result;
	}

	@Override
	public ReservationResult reservation(String reservation_info, Account acc,
			int useraction, String selection) throws IOException {
		final String branch_inputfield = "issuepoint";

		Document doc = null;

		String action = "reservation";
		if (reservation_info.contains("doBestellung")) {
			action = "order";
		}

		if (useraction == ReservationResult.ACTION_CONFIRMATION) {
			List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
			nameValuePairs.add(new BasicNameValuePair("methodToCall", action));
			nameValuePairs.add(new BasicNameValuePair("CSId", CSId));
			String html = httpPost(opac_url + "/" + action + ".do",
					new UrlEncodedFormEntity(nameValuePairs));
			doc = Jsoup.parse(html);
		} else if (selection == null || useraction == 0) {
			String html = httpGet(opac_url + "/availability.do?"
					+ reservation_info);
			doc = Jsoup.parse(html);

			if (doc.select("input[name=username]").size() > 0) {
				// Login vonnöten
				List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(
						2);
				nameValuePairs.add(new BasicNameValuePair("username", acc
						.getName()));
				nameValuePairs.add(new BasicNameValuePair("password", acc
						.getPassword()));
				nameValuePairs.add(new BasicNameValuePair("methodToCall",
						"submit"));
				nameValuePairs.add(new BasicNameValuePair("CSId", CSId));
				nameValuePairs.add(new BasicNameValuePair("login_action",
						"Login"));

				html = httpPost(opac_url + "/login.do",
						new UrlEncodedFormEntity(nameValuePairs));
				doc = Jsoup.parse(html);

				if (doc.getElementsByClass("error").size() == 0) {
					logged_in = System.currentTimeMillis();
					logged_in_as = acc;
				}
			}
			if (doc.select("input[name=" + branch_inputfield + "]").size() > 0) {
				ContentValues branches = new ContentValues();
				for (Element option : doc
						.select("input[name=" + branch_inputfield + "]")
						.first().parent().parent().parent().select("td")) {
					if (option.select("input").size() != 1)
						continue;
					String value = option.text().trim();
					String key = option.select("input").val();
					branches.put(key, value);
				}
				ReservationResult result = new ReservationResult(
						Status.SELECTION_NEEDED);
				result.setActionIdentifier(ReservationResult.ACTION_BRANCH);
				result.setSelection(branches);
				return result;
			}
		} else if (useraction == ReservationResult.ACTION_BRANCH) {
			List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
			nameValuePairs.add(new BasicNameValuePair(branch_inputfield,
					selection));
			nameValuePairs.add(new BasicNameValuePair("methodToCall", action));
			nameValuePairs.add(new BasicNameValuePair("CSId", CSId));

			String html = httpPost(opac_url + "/" + action + ".do",
					new UrlEncodedFormEntity(nameValuePairs));
			doc = Jsoup.parse(html);
		}

		if (doc == null)
			return new ReservationResult(Status.ERROR);

		if (doc.getElementsByClass("error").size() >= 1) {
			last_error = doc.getElementsByClass("error").get(0).text();
			return new ReservationResult(Status.ERROR);
		}

		if (doc.select("#CirculationForm p").size() > 0) {
			List<String[]> details = new ArrayList<String[]>();
			for (String row : doc.select("#CirculationForm p").first().html()
					.split("<br />")) {
				Document frag = Jsoup.parseBodyFragment(row);
				if (frag.text().contains(":")) {
					String[] split = frag.text().split(":");
					if (split.length >= 2)
						details.add(new String[] { split[0].trim() + ":",
								split[1].trim() });
				} else {
					details.add(new String[] { "", frag.text().trim() });
				}
			}
			ReservationResult result = new ReservationResult(
					Status.CONFIRMATION_NEEDED);
			result.setDetails(details);
			return result;
		}

		return new ReservationResult(Status.OK);
	}

	@Override
	public boolean prolong(Account account, String a) throws IOException,
			NotReachableException {
		// Internal convention: a is either a § followed by an error message or
		// the URI of the page this item was found on and the query string the
		// prolonging link links to, seperated by a $.
		if (a.startsWith("§")) {
			last_error = a.substring(1);
			return false;
		}
		String[] parts = a.split("\\$");
		String offset = parts[0];
		String query = parts[1];

		if (!initialised)
			start();
		if (System.currentTimeMillis() - logged_in > SESSION_LIFETIME
				|| logged_in_as == null) {
			try {
				account(account);
			} catch (JSONException e) {
				e.printStackTrace();
				return false;
			}
		} else if (logged_in_as.getId() != account.getId()) {
			try {
				account(account);
			} catch (JSONException e) {
				e.printStackTrace();
				return false;
			}
		}

		// We have to call the page we originally found the link on first...
		httpGet(opac_url + "/userAccount.do?methodToCall=showAccount&typ=1");
		if (offset != "1")
			httpGet(opac_url
					+ "/userAccount.do?methodToCall=pos&accountTyp=AUSLEIHEN&anzPos="
					+ offset);
		httpGet(opac_url + "/userAccount.do?" + query);

		return true;
	}

	@Override
	public boolean cancel(Account account, String a) throws IOException,
			NotReachableException {
		if (!initialised)
			start();

		String[] parts = a.split("\\$");
		String type = parts[0];
		String offset = parts[1];
		String query = parts[2];

		if (System.currentTimeMillis() - logged_in > SESSION_LIFETIME
				|| logged_in_as == null) {
			try {
				account(account);
			} catch (JSONException e) {
				e.printStackTrace();
				return false;
			}
		} else if (logged_in_as.getId() != account.getId()) {
			try {
				account(account);
			} catch (JSONException e) {
				e.printStackTrace();
				return false;
			}
		}

		// We have to call the page we originally found the link on first...
		httpGet(opac_url + "/userAccount.do?methodToCall=showAccount&typ="
				+ type);
		if (offset != "1")
			httpGet(opac_url + "/userAccount.do?methodToCall=pos&anzPos="
					+ offset);
		httpGet(opac_url + "/userAccount.do?" + query);
		return true;
	}

	protected boolean login(Account acc) {
		String html;
		List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
		nameValuePairs.add(new BasicNameValuePair("username", acc.getName()));
		nameValuePairs
				.add(new BasicNameValuePair("password", acc.getPassword()));
		nameValuePairs.add(new BasicNameValuePair("CSId", CSId));
		nameValuePairs.add(new BasicNameValuePair("methodToCall", "submit"));
		try {
			html = httpPost(opac_url + "/login.do", new UrlEncodedFormEntity(
					nameValuePairs));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return false;
		} catch (ClientProtocolException e) {
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		Document doc = Jsoup.parse(html);

		if (doc.getElementsByClass("error").size() > 0) {
			last_error = doc.getElementsByClass("error").get(0).text();
			return false;
		}

		logged_in = System.currentTimeMillis();
		logged_in_as = acc;

		return true;
	}

	protected void parse_medialist(List<ContentValues> medien, Document doc,
			int offset) throws ClientProtocolException, IOException {
		Elements copytrs = doc.select(".data tr");
		doc.setBaseUri(opac_url);

		SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");

		int trs = copytrs.size();
		if (trs == 1)
			return;
		assert (trs > 0);
		for (int i = 1; i < trs; i++) {
			Element tr = copytrs.get(i);
			ContentValues e = new ContentValues();

			if (tr.text().contains("keine Daten")) {
				return;
			}

			e.put(AccountData.KEY_LENT_TITLE, tr.child(1).select("strong")
					.text().trim());
			try {
				e.put(AccountData.KEY_LENT_AUTHOR,
						tr.child(1).html().split("<br />")[1].trim());

				String frist = tr.child(2).html().split("<br />")[0].trim();
				if (frist.contains("-"))
					frist = frist.split("-")[1].trim();
				e.put(AccountData.KEY_LENT_DEADLINE, frist);
				e.put(AccountData.KEY_LENT_BRANCH,
						tr.child(2).html().split("<br />")[1].trim());

				if (!frist.equals("")) {
					try {
						e.put(AccountData.KEY_LENT_DEADLINE_TIMESTAMP,
								sdf.parse(
										e.getAsString(AccountData.KEY_LENT_DEADLINE))
										.getTime());
					} catch (ParseException e1) {
						e1.printStackTrace();
					}
				}

				if (tr.select("a").size() > 0) {
					for (Element link : tr.select("a")) {
						Uri uri = Uri.parse(link.attr("abs:href"));
						if (uri.getQueryParameter("methodToCall").equals(
								"renewalPossible")) {
							e.put(AccountData.KEY_LENT_LINK,
									offset + "$" + uri.getQuery());
							break;
						}
					}
				} else if (tr.select(".textrot, .textgruen").size() == 1) {
					e.put(AccountData.KEY_LENT_LINK,
							"§" + tr.select(".textrot, .textgruen").text());
				}

			} catch (Exception ex) {
				ex.printStackTrace();
			}

			medien.add(e);
		}
		assert (medien.size() == trs - 1);

	}

	protected void parse_reslist(String type, List<ContentValues> reservations,
			Document doc, int offset) throws ClientProtocolException,
			IOException {
		Elements copytrs = doc.select(".data tr");
		doc.setBaseUri(opac_url);

		int trs = copytrs.size();
		if (trs == 1)
			return;
		assert (trs > 0);
		for (int i = 1; i < trs; i++) {
			Element tr = copytrs.get(i);
			ContentValues e = new ContentValues();

			if (tr.text().contains("keine Daten")) {
				return;
			}

			e.put(AccountData.KEY_RESERVATION_TITLE,
					tr.child(1).select("strong").text().trim());
			try {
				e.put(AccountData.KEY_RESERVATION_AUTHOR, tr.child(1).html()
						.split("<br />")[1].trim());

				e.put(AccountData.KEY_RESERVATION_BRANCH, tr.child(2).html()
						.split("<br />")[2].trim());

				if (tr.select("a").size() == 1)
					e.put(AccountData.KEY_RESERVATION_CANCEL,
							type
									+ "$"
									+ offset
									+ "$"
									+ Uri.parse(tr.select("a").attr("abs:href"))
											.getQuery());

			} catch (Exception ex) {
				ex.printStackTrace();
			}

			reservations.add(e);
		}
		assert (reservations.size() == trs - 1);
	}

	protected String httpGet(String url) throws ClientProtocolException,
			IOException {
		HttpGet httpget = new HttpGet(url);
		HttpResponse response = ahc.execute(httpget);
		if (response.getStatusLine().getStatusCode() >= 400) {
			throw new NotReachableException();
		}
		String html = convertStreamToString(response.getEntity().getContent());
		response.getEntity().consumeContent();
		return html;
	}

	protected String httpPost(String url, UrlEncodedFormEntity data)
			throws ClientProtocolException, IOException {
		HttpPost httppost = new HttpPost(url);
		httppost.setEntity(data);
		HttpResponse response = ahc.execute(httppost);
		if (response.getStatusLine().getStatusCode() >= 400) {
			throw new NotReachableException();
		}
		String html = convertStreamToString(response.getEntity().getContent());
		response.getEntity().consumeContent();
		return html;
	}

	@Override
	public AccountData account(Account acc) throws IOException,
			NotReachableException, JSONException, SocketException {
		start(); // TODO: Is this necessary?

		int resultNum;

		if (!login(acc))
			return null;

		// Geliehene Medien
		String html = httpGet(opac_url
				+ "/userAccount.do?methodToCall=showAccount&typ=1");
		List<ContentValues> medien = new ArrayList<ContentValues>();
		Document doc = Jsoup.parse(html);
		doc.setBaseUri(opac_url);
		parse_medialist(medien, doc, 1);
		for (Element link : doc.select(".box-right").first().select("a")) {
			Uri uri = Uri.parse(link.attr("abs:href"));
			if (uri == null || uri.getQueryParameter("methodToCall") == null)
				continue;
			if (uri.getQueryParameter("methodToCall").equals("pos")) {
				html = httpGet(uri.toString());
				parse_medialist(medien, Jsoup.parse(html),
						Integer.parseInt(uri.getQueryParameter("anzPos")));
			}
		}
		if (doc.select("#label1").size() > 0) {
			resultNum = Integer.parseInt(doc.select("#label1").text().trim()
					.replaceAll(".*\\(([0-9]+)\\).*", "$1"));
			assert (resultNum == medien.size());
		}

		// Bestellte Medien
		html = httpGet(opac_url
				+ "/userAccount.do?methodToCall=showAccount&typ=6");
		List<ContentValues> reserved = new ArrayList<ContentValues>();
		doc = Jsoup.parse(html);
		doc.setBaseUri(opac_url);
		parse_reslist("6", reserved, doc, 1);
		Elements label6 = doc.select("#label6");
		for (Element link : doc.select(".box-right").first().select("a")) {
			Uri uri = Uri.parse(link.attr("abs:href"));
			if (uri == null || uri.getQueryParameter("methodToCall") == null)
				break;
			if (uri.getQueryParameter("methodToCall").equals("pos")) {
				html = httpGet(uri.toString());
				parse_reslist("6", medien, Jsoup.parse(html),
						Integer.parseInt(uri.getQueryParameter("anzPos")));
			}
		}

		// Vorgemerkte Medien
		html = httpGet(opac_url
				+ "/userAccount.do?methodToCall=showAccount&typ=7");
		doc = Jsoup.parse(html);
		doc.setBaseUri(opac_url);
		parse_reslist("7", reserved, doc, 1);
		for (Element link : doc.select(".box-right").first().select("a")) {
			Uri uri = Uri.parse(link.attr("abs:href"));
			if (uri == null || uri.getQueryParameter("methodToCall") == null)
				break;
			if (uri.getQueryParameter("methodToCall").equals("pos")) {
				html = httpGet(uri.toString());
				parse_reslist("7", medien, Jsoup.parse(html),
						Integer.parseInt(uri.getQueryParameter("anzPos")));
			}
		}
		if (label6.size() > 0 && doc.select("#label7").size() > 0) {
			resultNum = Integer.parseInt(label6.text().trim()
					.replaceAll(".*\\(([0-9]+)\\).*", "$1"));
			resultNum += Integer.parseInt(doc.select("#label7").text().trim()
					.replaceAll(".*\\(([0-9]+)\\).*", "$1"));
			assert (resultNum == reserved.size());
		}

		AccountData res = new AccountData(acc.getId());
		res.setLent(medien);
		res.setReservations(reserved);
		return res;
	}

	@Override
	public boolean isAccountSupported(Library library) {
		return true;
	}

	@Override
	public boolean isAccountExtendable() {
		return false;
	}

	@Override
	public String getAccountExtendableInfo(Account acc)
			throws ClientProtocolException, SocketException, IOException,
			NotReachableException {
		return null;
	}

	@Override
	public String getShareUrl(String id, String title) {
		String startparams = "";
		if (data.has("startparams")) {
			try {
				startparams = data.getString("startparams") + "&";
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		if (id != null && id != "")
			return opac_url + "/start.do?" + startparams
					+ "searchType=1&Query=0%3D%22" + id + "%22";
		else
			return opac_url + "/start.do?" + startparams
					+ "searchType=1&Query=-1%3D%22" + title + "%22";
	}

	@Override
	public int getSupportFlags() {
		return 0;
	}

	@Override
	public boolean prolongAll(Account account) throws IOException {
		return false;
	}

	@Override
	public SearchRequestResult filterResults(Filter filter, Option option)
			throws IOException, NotReachableException {
		// TODO Auto-generated method stub
		return null;
	}
}
