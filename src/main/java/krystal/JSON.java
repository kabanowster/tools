package krystal;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@UtilityClass
@Log4j2
public class JSON {
	
	/**
	 * Serialize Object into {@link JSONObject}.
	 */
	public JSONObject from(Object obj) {
		if (obj == null) return new JSONObject();
		
		val flattison = flattison(obj);
		if (Map.class.isAssignableFrom(flattison.getClass())) {
			return new JSONObject((Map<?, ?>) flattison);
		} else {
			return new JSONObject(Map.of("list", flattison));
		}
	}
	
	public JSONObject from(Object obj, String... fields) {
		return new JSONObject(from(obj), fields);
	}
	
	private Object flattison(Object obj) {
		if (obj == null) return null;
		val clazz = obj.getClass();
		
		if (Map.class.isAssignableFrom(clazz)) {
			val flattisonMap = new HashMap<>();
			((Map<?, ?>) obj).forEach((k, v) -> flattisonMap.put(k, flattison(v)));
			return flattisonMap;
		} else if (Collection.class.isAssignableFrom(clazz)) {
			val flattisonList = new LinkedList<>();
			((Collection<?>) obj).forEach(v -> flattisonList.add(flattison(v)));
			return flattisonList;
		} else if (clazz.isAnnotationPresent(Flattison.class)) {
			return flattisonObject(obj);
		} else return obj;
	}
	
	public Map<?, ?> flattisonObject(Object obj) {
		val clazz = obj.getClass();
		val fieldsMap = new HashMap<>();
		
		Stream.of(clazz.getDeclaredFields()).forEach(f -> {
			if (f.isAnnotationPresent(Skipson.class)) return;
			
			Object value = null;
			if (f.trySetAccessible()) {
				try {
					value = flattison(f.get(obj));
				} catch (IllegalAccessException ignored) {
				}
			}
			fieldsMap.put(f.getName(), value);
		});
		return fieldsMap;
	}
	
	/**
	 * Use this function to deserialize JSON object into provided class. Also works with collections. {@link Flattison} classes also require No-Args constructor.
	 *
	 * @param json
	 *        {@link JSONObject}, {@link JSONArray} or object of class marked as {@link Flattison}. Otherwise, this function returns object as it is;
	 * @param clazz
	 * 		Class of the provided json object to be deserialized into;
	 * @param innerTypes
	 * 		If the provided json object is a collection, this parameter is mendatory to determine the classes of the child elements.
	 */
	public Object into(Object json, Class<?> clazz, Type... innerTypes) {
		
		if (json instanceof JSONArray jsonArray) {
			// value is a collection of List or Set
			
			val collection = Set.class.isAssignableFrom(clazz) ? new LinkedHashSet<>() : new LinkedList<>();
			
			Class<?> elementClass = Object.class;
			var arguments = new Type[0];
			try {
				val elementType = innerTypes[0];
				
				if (elementType instanceof ParameterizedType t) {
					elementClass = Class.forName(t.getRawType().getTypeName());
					arguments = t.getActualTypeArguments();
				} else {
					elementClass = Class.forName(elementType.getTypeName());
				}
				
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IndexOutOfBoundsException ignored) {
			}
			
			for (var element : jsonArray) collection.add(into(element, elementClass, arguments));
			return clazz.isArray() ? collection.toArray() : collection;
			
		} else if (json instanceof JSONObject jsonObject) {
			if (Map.class.isAssignableFrom(clazz)) {
				// value is a declared map
				
				val map = new HashMap<>();
				
				Class<?> elementClass = Objects.class;
				var arguments = new Type[0];
				try {
					val elementType = innerTypes[1];
					
					if (elementType instanceof ParameterizedType t) {
						elementClass = Class.forName(t.getRawType().getTypeName());
						arguments = t.getActualTypeArguments();
					} else {
						elementClass = Class.forName(elementType.getTypeName());
					}
					
				} catch (ClassNotFoundException e) {
					throw new RuntimeException(e);
				} catch (IndexOutOfBoundsException ignored) {
				}
				
				for (var element : jsonObject.keySet())
					map.put(element, into(jsonObject.get(element), elementClass, arguments));
				
				return map;
				
			} else if (clazz.isAnnotationPresent(Flattison.class)) {
				// value isc declared serializable object
				
				try {
					val result = clazz.getDeclaredConstructor().newInstance();
					
					// methods marked deserializers and setters
					val deserializers =
							Stream.of(clazz.getDeclaredMethods())
							      .filter(m -> m.trySetAccessible() && (m.isAnnotationPresent(Deserializer.class) || m.getName().toLowerCase().startsWith("set")))
							      .collect(Collectors.toMap(
									      m -> {
										      var name = m.getName();
										      try {
											      name = m.getAnnotation(Deserializer.class).fieldName();
										      } catch (NullPointerException e) {
											      try {
												      name = name.substring(3);
											      } catch (IndexOutOfBoundsException ignored) {
											      }
										      }
										      if (name.isEmpty()) name = m.getName();
										      return name.toLowerCase();
									      },
									      m -> m,
									      (a, b) -> b.isAnnotationPresent(Deserializer.class) ? b : a
							      ));
					
					Stream.of(clazz.getDeclaredFields())
					      .filter(f -> !f.isAnnotationPresent(Skipson.class) && f.trySetAccessible())
					      .forEach(f -> {
						      val name = f.getName();
						      val type = f.getType();
						      val arguments = f.getGenericType() instanceof ParameterizedType t ? t.getActualTypeArguments() : new Type[0];
						      
						      Object value = null;
						      try {
							      value = jsonObject.get(name);
							      value = into(value, type, arguments);
							      f.set(result, value);
						      } catch (JSONException ignored) {
							      log.info("[JSON Deserialization] Value for the key not found in JSON source. Case sensitive! Skipped field: %s".formatted(name));
						      } catch (IllegalArgumentException e) {
							      val deserializer = deserializers.get(name.toLowerCase());
							      if (deserializer != null) {
								      try {
									      deserializer.invoke(result, value);
								      } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException x) {
									      log.warn("[JSON Deserialization] Value can not be written to field of type %s. Check for missing @Deserializer or Setter method. Case insensitive! Skipped field: %s".formatted(type.getSimpleName(), name));
								      }
							      }
						      } catch (IllegalAccessException e) {
							      throw new RuntimeException(e);
						      }
					      });
					
					return result;
				} catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
					throw new RuntimeException("No-Args constructor is missing for %s class.".formatted(clazz), e);
				}
				
			}
		} else {
			
			// json is just a regular value of type clazz
			return json;
		}
		
		return null;
	}
	
	/**
	 * Mark class that will be (de-)serialized to JSON using fields values directly and recursively - for stored {@link Flattison} objects and collections.
	 */
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Flattison {
	
	}
	
	/**
	 * Mark fields to be skipped from (de-)serializations.
	 */
	@Target(ElementType.FIELD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Skipson {
	
	}
	
	/**
	 * Mark setter method to be used in deserialization for the given field. Usable for fields of type other than Map, List or {@link Flattison} class and outside primitive scope. I.e. Interfaces, Enums, Abstractions.
	 */
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Deserializer {
		
		String fieldName();
		
	}
	
}