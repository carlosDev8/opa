/**
 * Copyright (C) 2014 by Johan von Forstner under the MIT license:
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), 
 * to deal in the Software without restriction, including without limitation 
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the Software 
 * is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, 
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER 
 * DEALINGS IN THE SOFTWARE.
 */
package de.geeksfactory.opacclient.apis;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.geeksfactory.opacclient.NotReachableException;
import de.geeksfactory.opacclient.i18n.StringProvider;
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
import de.geeksfactory.opacclient.searchfields.DropdownSearchField;
import de.geeksfactory.opacclient.searchfields.SearchField;
import de.geeksfactory.opacclient.searchfields.SearchQuery;
import de.geeksfactory.opacclient.searchfields.TextSearchField;

/**
 * 
 * @author Johan von Forstner, 06.04.2014
 * 
 *         WebOPAC.net, Version 2.2.70 gestartet mit Gemeindebibliothek
 *         Nürensdorf (erstes Google-Suchergebnis)
 * 
 *         weitere kompatible Bibliotheken:
 *         https://www.google.de/search?q=webOpac
 *         .net%202.1.30%20powered%20by%20winMedio
 *         .net&qscrl=1#q=%22webOpac.net+2.2
 *         .70+powered+by+winMedio.net%22+inurl%3Awinmedio&qscrl=1&start=0
 * 
 *         Unterstützt bisher nur Katalogsuche, Accountunterstüzung könnte (wenn
 *         keine Kontodaten verfügbar sind) über den Javascript-Code
 *         reverse-engineered werden:
 *         http://www.winmedio.net/nuerensdorf/de/mobile
 *         /GetScript.ashx?id=mobile.de.min.js&v=20140122
 * 
 */

public class WebOpacNet extends BaseApi implements OpacApi {

	protected String opac_url = "";
	protected JSONObject data;
	protected Library library;
	protected List<SearchQuery> query;

	protected static HashMap<String, MediaType> defaulttypes = new HashMap<String, MediaType>();

	static {
		defaulttypes.put("1", MediaType.BOOK);
		defaulttypes.put("2", MediaType.CD_MUSIC);
		defaulttypes.put("3", MediaType.AUDIOBOOK);
		defaulttypes.put("4", MediaType.DVD);
		defaulttypes.put("5", MediaType.CD_SOFTWARE);
		defaulttypes.put("8", MediaType.MAGAZINE);
	}

	@Override
	public void init(Library lib) {
		super.init(lib);

		this.library = lib;
		this.data = lib.getData();

		try {
			this.opac_url = data.getString("baseurl");
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public SearchRequestResult search(List<SearchQuery> query)
			throws IOException, NotReachableException, OpacErrorException,
			JSONException {
		this.query = query;
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		start();

		int index = buildParams(query, params, 1);

		if (index == 0) {
			throw new OpacErrorException(
					stringProvider.getString(StringProvider.NO_CRITERIA_INPUT));
		}

		String json = httpGet(opac_url + "/de/mobile/GetMedien.ashx"
				+ buildHttpGetParams(params, getDefaultEncoding()),
				getDefaultEncoding());

		return parse_search(json, 1);
	}

	protected int addParameters(SearchQuery query, StringBuilder params,
			int index) {
		if (query.getValue().equals(""))
			return index;
		if (index > 0)
			params.append("$0");
		params.append("|" + query.getKey() + "|" + query.getValue());
		return index + 1;
	}

	private SearchRequestResult parse_search(String text, int page)
			throws OpacErrorException {
		if (!text.equals("")) {
			try {
				List<SearchResult> results = new ArrayList<SearchResult>();
				JSONObject json = new JSONObject(text);
				int total_result_count = Integer.parseInt(json
						.getString("totalcount"));

				JSONArray resultList = json.getJSONArray("mobmeds");
				for (int i = 0; i < resultList.length(); i++) {
					JSONObject resultJson = resultList.getJSONObject(i);
					SearchResult result = new SearchResult();
					result.setId(resultJson.getString("medid"));

					String title = resultJson.getString("titel");
					String publisher = resultJson.getString("verlag");
					String series = resultJson.getString("reihe");
					String html = "<b>" + title + "</b><br />" + publisher
							+ ", " + series;

					String type = resultJson.getString("iconurl").substring(12,
							13);
					result.setType(defaulttypes.get(type));

					result.setInnerhtml(html);

					if (resultJson.getString("imageurl").length() > 0)
						result.setCover(resultJson.getString("imageurl"));

					results.add(result);
				}

				return new SearchRequestResult(results, total_result_count,
						page);
			} catch (JSONException e) {
				e.printStackTrace();
				throw new OpacErrorException(stringProvider.getFormattedString(
						StringProvider.INTERNAL_ERROR_WITH_DESCRIPTION,
						e.getMessage()));
			}
		} else {
			return new SearchRequestResult(new ArrayList<SearchResult>(), 0,
					page);
		}

	}

	@Override
	public SearchRequestResult filterResults(Filter filter, Option option)
			throws IOException, NotReachableException, OpacErrorException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SearchRequestResult searchGetPage(int page) throws IOException,
			NotReachableException, OpacErrorException, JSONException {
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		start();

		int index = buildParams(query, params, page);

		if (index == 0) {
			throw new OpacErrorException(
					stringProvider.getString(StringProvider.NO_CRITERIA_INPUT));
		}

		String json = httpGet(opac_url + "/de/mobile/GetMedien.ashx"
				+ buildHttpGetParams(params, getDefaultEncoding()),
				getDefaultEncoding());

		return parse_search(json, page);
	}

	private int buildParams(List<SearchQuery> queryList,
			List<NameValuePair> params, int page) throws JSONException {
		int index = 0;

		StringBuilder queries = new StringBuilder();
		queries.append("erw:0");
		for (SearchQuery query : queryList) {
			if (!query.getSearchField().getData().getBoolean("filter"))
				index = addParameters(query, queries, index);
		}

		for (SearchQuery query : queryList) {
			if (query.getSearchField().getData().getBoolean("filter")
					&& !query.getValue().equals(""))
				queries.append("&" + query.getKey() + "=" + query.getValue());
		}

		params.add(new BasicNameValuePair("q", queries.toString()));
		params.add(new BasicNameValuePair("p", String.valueOf(page - 1)));
		params.add(new BasicNameValuePair("t", "1"));

		return index;
	}

	@Override
	public DetailledItem getResultById(String id, String homebranch)
			throws IOException, NotReachableException, OpacErrorException {
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("id", id));
		params.add(new BasicNameValuePair("orientation", "1"));

		String json = httpGet(opac_url + "/de/mobile/GetDetail.ashx"
				+ buildHttpGetParams(params, getDefaultEncoding()),
				getDefaultEncoding());

		return parse_detail(json);
	}

	private DetailledItem parse_detail(String text) throws OpacErrorException {
		try {
			DetailledItem result = new DetailledItem();
			JSONObject json = new JSONObject(text);

			result.setTitle(json.getString("titel"));
			result.setCover(json.getString("imageurl"));
			result.setId(json.getString("medid"));

			// Details
			JSONArray info = json.getJSONArray("medium");
			for (int i = 0; i < info.length(); i++) {
				JSONObject detailJson = info.getJSONObject(i);
				String name = detailJson.getString("bez");
				String value = "";

				JSONArray values = detailJson.getJSONArray("values");
				for (int j = 0; j < values.length(); j++) {
					JSONObject valJson = values.getJSONObject(j);
					if (j != 0)
						value += ", ";
					String content = valJson.getString("dval");
					content = content.replaceAll("<span[^>]*>", "");
					content = content.replaceAll("</span>", "");
					value += content;
				}
				Detail detail = new Detail(name, value);
				result.addDetail(detail);
			}

			// Copies
			JSONArray copies = json.getJSONArray("exemplare");
			for (int i = 0; i < copies.length(); i++) {
				JSONObject copyJson = copies.getJSONObject(i);
				Map<String, String> copy = new HashMap<String, String>();

				JSONArray values = copyJson.getJSONArray("rows");
				for (int j = 0; j < values.length(); j++) {
					JSONObject valJson = values.getJSONObject(j);
					String name = valJson.getString("bez");
					String value = valJson.getJSONArray("values")
							.getJSONObject(0).getString("dval");
					if (!value.equals("")) {
						if (name.equals("Exemplarstatus"))
							copy.put(DetailledItem.KEY_COPY_STATUS, value);
						else if (name.equals("Signatur"))
							copy.put(DetailledItem.KEY_COPY_SHELFMARK, value);
						else if (name.equals("Standort"))
							copy.put(DetailledItem.KEY_COPY_LOCATION, value);
						else if (name.equals("Themenabteilung")) {
							if (copy.containsKey(DetailledItem.KEY_COPY_DEPARTMENT))
								value = copy
										.get(DetailledItem.KEY_COPY_DEPARTMENT)
										+ value;
							copy.put(DetailledItem.KEY_COPY_DEPARTMENT, value);
						} else if (name.equals("Themenbereich")) {
							if (copy.containsKey(DetailledItem.KEY_COPY_DEPARTMENT))
								value = copy
										.get(DetailledItem.KEY_COPY_DEPARTMENT)
										+ value;
							copy.put(DetailledItem.KEY_COPY_DEPARTMENT, value);
						}
					}
				}
				result.addCopy(copy);
			}

			return result;

		} catch (JSONException e) {
			e.printStackTrace();
			throw new OpacErrorException(stringProvider.getFormattedString(
					StringProvider.INTERNAL_ERROR_WITH_DESCRIPTION,
					e.getMessage()));
		}

	}

	@Override
	public DetailledItem getResult(int position) throws IOException,
			OpacErrorException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ReservationResult reservation(DetailledItem item, Account account,
			int useraction, String selection) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ProlongResult prolong(String media, Account account, int useraction,
			String selection) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ProlongAllResult prolongAll(Account account, int useraction,
			String selection) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CancelResult cancel(String media, Account account, int useraction,
			String selection) throws IOException, OpacErrorException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AccountData account(Account account) throws IOException,
			JSONException, OpacErrorException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<SearchField> getSearchFields() throws IOException,
			JSONException {
		List<SearchField> fields = new ArrayList<SearchField>();

		// Text fields
		String html = httpGet(opac_url + "/de/mobile/default.aspx",
				getDefaultEncoding());
		Document doc = Jsoup.parse(html);
		Elements options = doc.select("#drpOptSearchT option");
		for (Element option : options) {
			TextSearchField field = new TextSearchField();
			field.setDisplayName(option.text());
			field.setId(option.attr("value"));
			field.setData(new JSONObject("{\"filter\":false}"));
			field.setHint("");
			fields.add(field);
		}

		// Dropdowns
		String text = httpGet(opac_url + "/de/mobile/GetRestrictions.ashx",
				getDefaultEncoding());
		JSONArray filters = new JSONObject(text)
				.getJSONArray("restrcontainers");
		for (int i = 0; i < filters.length(); i++) {
			JSONObject filter = filters.getJSONObject(i);
			if (filter.getString("querytyp").equals("EJ")) {
				// Querying by year also works for other years than the ones
				// listed
				// -> Make it a text field instead of a dropdown
				TextSearchField field = new TextSearchField();
				field.setDisplayName(filter.getString("kopf"));
				field.setId(filter.getString("querytyp"));
				field.setData(new JSONObject("{\"filter\":true}"));
				field.setHint("");
				fields.add(field);
			} else {
				DropdownSearchField field = new DropdownSearchField();
				field.setId(filter.getString("querytyp"));
				field.setDisplayName(filter.getString("kopf"));

				JSONArray restrictions = filter.getJSONArray("restrictions");
				List<Map<String, String>> values = new ArrayList<Map<String, String>>();

				Map<String, String> all = new HashMap<String, String>();
				all.put("key", "");
				all.put("value", "Alle");
				values.add(all);

				for (int j = 0; j < restrictions.length(); j++) {
					JSONObject restriction = restrictions.getJSONObject(j);
					Map<String, String> value = new HashMap<String, String>();
					value.put("key", restriction.getString("id"));
					value.put("value", restriction.getString("bez"));
					values.add(value);
				}

				field.setDropdownValues(values);
				field.setData(new JSONObject("{\"filter\":true}"));
				fields.add(field);
			}
		}

		return fields;
	}

	@Override
	public boolean isAccountSupported(Library library) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	@Deprecated
	public boolean isAccountExtendable() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getAccountExtendableInfo(Account account) throws IOException,
			NotReachableException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getShareUrl(String id, String title) {
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("id", id));

		String url;
		try {
			url = opac_url + "/default.aspx"
					+ buildHttpGetParams(params, getDefaultEncoding());
			return url;
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		return null;
	}

	@Override
	public int getSupportFlags() {
		return SUPPORT_FLAG_ENDLESS_SCROLLING | SUPPORT_FLAG_CHANGE_ACCOUNT;
	}

	private String buildHttpGetParams(List<NameValuePair> params,
			String encoding) throws UnsupportedEncodingException {
		String string = "?";
		for (NameValuePair pair : params) {
			String name = URLEncoder.encode(pair.getName(), encoding);
			String value = URLEncoder.encode(pair.getValue(), encoding);
			string += name + "=" + value + "&";
		}
		string = string.substring(0, string.length() - 1);
		return string;
	}

	@Override
	protected String getDefaultEncoding() {
		return "UTF-8";
	}

	@Override
	public void checkAccountData(Account account) throws IOException,
			JSONException, OpacErrorException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setLanguage(String language) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Set<String> getSupportedLanguages() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
