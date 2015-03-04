package de.geeksfactory.opacclient.searchfields;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * A DropdownSearchField allows the user to select from a list of values, e.g.
 * library branches or item formats.
 */
public class DropdownSearchField extends SearchField {

	protected List<Map<String, String>> dropdownValues;

	public DropdownSearchField() {

	}

	/**
	 * A new dropdown SearchField
	 * 
	 * @param id
	 *            ID of the search field, later given to your search() function
	 * @param displayName
	 *            The name to display for the search field
	 * @param advanced
	 *            Set if this field should only be shown when showing the
	 *            advanced search form
	 * @param dropdownValues
	 *            The values to show in the dropdown and their keys. If you
	 *            include one with an empty key, this is going to be the default
	 *            value and will not be given to the search() function
	 */
	public DropdownSearchField(String id, String displayName, boolean advanced,
			List<Map<String, String>> dropdownValues) {
		super(id, displayName, advanced);
		this.dropdownValues = dropdownValues;
	}

	/**
	 * Get the list of selectable values.
	 */
	public List<Map<String, String>> getDropdownValues() {
		return dropdownValues;
	}

	/**
	 * Set a list of values for the dropdown list. Each value is a Map and
	 * should contain the keys 'key' and 'value'.
	 */
	public void setDropdownValues(List<Map<String, String>> dropdownValues) {
		this.dropdownValues = dropdownValues;
	}

	@Override
	public JSONObject toJSON() throws JSONException {
		JSONObject json = super.toJSON();
		json.put("type", "dropdown");
		JSONArray values = new JSONArray();
		for (Map<String, String> map : dropdownValues) {
			JSONObject value = new JSONObject();
			value.put("key", map.get("key"));
			value.put("value", map.get("value"));
			values.put(value);
		}
		json.put("dropdownValues", values);
		return json;
	}

}
