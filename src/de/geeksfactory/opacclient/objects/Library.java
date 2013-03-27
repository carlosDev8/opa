package de.geeksfactory.opacclient.objects;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Object representing a supported library
 * 
 * @author Raphael Michel
 */
public class Library implements Comparable<Library> {
	private String ident;
	private String city;
	private String title;
	private String support;
	private String api;
	private JSONObject data;
	private String group;
	private double[] geo;

	/**
	 * Create a Library object based on a <code>JSONObject</code>.
	 * 
	 * @param ident
	 *            identifier
	 * @param input
	 *            input data
	 * @return new Library object
	 * @throws JSONException
	 *             if parsing failed or objects were missing
	 * @see #getIdent()
	 */
	public static Library fromJSON(String ident, JSONObject input)
			throws JSONException {
		Library lib = new Library();
		lib.setIdent(ident);
		lib.setApi(input.getString("api"));
		lib.setCity(input.getString("city"));
		lib.setTitle(input.getString("title"));
		lib.setSupport(input.getString("support"));
		lib.setGroup(input.getString("group"));
		lib.setData(input.getJSONObject("data"));
		if (input.has("geo")) {
			double[] geo = new double[2];
			geo[0] = input.getJSONArray("geo").getDouble(0);
			geo[1] = input.getJSONArray("geo").getDouble(1);
			lib.setGeo(geo);
		}

		if (lib.getTitle().equals(""))
			lib.setTitle(null);
		if (lib.getSupport().equals(""))
			lib.setSupport(null);
		return lib;
	}

	/**
	 * Get the library's identifier (in OpacClient app this is the filename in
	 * assets/bibs/ without the .json extension)
	 * 
	 * @return unique library identifier
	 */
	public String getIdent() {
		return ident;
	}

	/**
	 * Set the library's unique identifier.
	 * 
	 * @param ident
	 *            Identifier
	 */
	public void setIdent(String ident) {
		this.ident = ident;
	}

	/**
	 * Get the city the library is located in
	 * 
	 * @return city name
	 */
	public String getCity() {
		return city;
	}

	/**
	 * Set the city the library is located in
	 * 
	 * @param city
	 *            city name
	 */
	public void setCity(String city) {
		this.city = city;
	}

	/**
	 * Get an additional name of the library if it is not the main public
	 * library in the city it is located in
	 * 
	 * @return a title, not including the city's name
	 */
	public String getTitle() {
		return title;
	}

	/**
	 * Set an additional name of the library if it is not the main public
	 * library in the city it is located in
	 * 
	 * @param title
	 *            a title, not including the city's name
	 */
	public void setTitle(String title) {
		this.title = title;
	}

	/**
	 * Get a human-readable string describing what features are supported in
	 * this library
	 * 
	 * @return Support string
	 */
	public String getSupport() {
		return support;
	}

	/**
	 * Set a human-readable string describing what features are supported in
	 * this library
	 * 
	 * @param support
	 *            Support string
	 */
	public void setSupport(String support) {
		this.support = support;
	}

	/**
	 * Get the name of the API implementation used for this library
	 * 
	 * @return API implementation
	 */
	public String getApi() {
		return api;
	}

	/**
	 * Set the name of the API implementation used for this library
	 * 
	 * @param api
	 *            API implementation (like "bond26")
	 */
	public void setApi(String api) {
		this.api = api;
	}

	/**
	 * Get additional data from JSON configuration
	 * 
	 * @return "data" object from JSON file
	 */
	public JSONObject getData() {
		return data;
	}

	/**
	 * Set additional data from JSON configuration
	 * 
	 * @param data
	 *            "data" object from JSON file
	 */
	public void setData(JSONObject data) {
		this.data = data;
	}

	/**
	 * Get group the library belongs to
	 * 
	 * @return Group label
	 */
	public String getGroup() {
		return group;
	}

	/**
	 * Set group the library belongs to
	 * 
	 * @param group
	 *            Group label
	 */
	public void setGroup(String group) {
		this.group = group;
	}

	/**
	 * Get latitude and longitude of the library's geolocation
	 * 
	 * @return Array of latitude and longitude
	 */
	public double[] getGeo() {
		return geo;
	}

	/**
	 * Set latitude and longitude of the library's geolocation
	 * 
	 * @param geo
	 *            Array of latitude and longitude
	 */
	public void setGeo(double[] geo) {
		this.geo = geo;
	}

	public int compareTo(Library arg0) {
		int g = group.compareTo(arg0.getGroup());
		if (g == 0)
			g = city.compareTo(arg0.getCity());
		return g;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((ident == null) ? 0 : ident.hashCode());
		return result;
	}

	/**
	 * Evaluates, whether this object represents the same library as the given
	 * one. Only the library ident (aka. filename) is taken into consideration!
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Library other = (Library) obj;
		if (ident == null) {
			if (other.ident != null)
				return false;
		} else if (!ident.equals(other.ident))
			return false;
		return true;
	}

}
