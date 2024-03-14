package krystal;

import lombok.val;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public interface JSONSerialization {
	
	default JSONObject json() {
		val clazz = this.getClass();
		val fieldsMap = new HashMap<>();
		
		Stream.of(clazz.getFields()).forEach(f -> {
			Object value = null;
			if (f.trySetAccessible()) {
				try {
					value = serialize(f.get(this));
				} catch (IllegalAccessException ignored) {
				}
			}
			fieldsMap.put(f.getName(), value);
		});
		return new JSONObject(fieldsMap);
	}
	
	// static <T> T into(String json, Class<T> clazz) {
	//
	// }
	
	static Object serialize(Object value) {
		val clazz = value.getClass();
		if (Map.class.isAssignableFrom(clazz)) {
			val result = new HashMap<>();
			((Map<?, ?>) value).forEach((k, v) -> result.put(k, serialize(v)));
			return result;
		} else if (Collection.class.isAssignableFrom(clazz)) {
			val result = new ArrayList<>();
			((Collection<?>) value).forEach(f -> result.add(serialize(f)));
			return result;
		} else if (JSONSerialization.class.isAssignableFrom(clazz)) {
			return ((JSONSerialization) value).json();
		} else return value;
	}
	
}