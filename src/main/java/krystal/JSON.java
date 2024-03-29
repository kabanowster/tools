package krystal;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.javatuples.Pair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class wrapping {@link JSONObject} utilities, allowing for deep (de)serialization of objects.
 */
@UtilityClass
@Log4j2
public class JSON {
	
	/**
	 * Serialize Object into {@link JSONObject}. Fields marked with {@link Skipson Skipson} will be skipped.
	 *
	 * @param obj
	 *        {@link Collection}, {@link Map}, array or object of class marked with {@link Flattison Flattison}.
	 * @return If null obj provided - will return empty {@link JSONObject}.
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
			val flattisonMap = new HashMap<>(); // nullable values
			((Map<?, ?>) obj).forEach((k, v) -> flattisonMap.put(k, flattison(v)));
			return flattisonMap;
		} else if (Collection.class.isAssignableFrom(clazz) || clazz.isArray()) {
			return (clazz.isArray() ? Stream.of((Object[]) obj) : ((Collection<?>) obj).stream())
					.map(JSON::flattison)
					.toList();
		} else if (clazz.isAnnotationPresent(Flattison.class)) {
			val fieldsMap = new HashMap<>(); // nullable values
			
			Stream.of(clazz.getDeclaredFields())
			      .forEach(f -> {
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
			
		} else return obj;
	}
	
	/**
	 * Use this function to deserialize JSON object into provided {@link Flattison Flattison} class and recursively it's members. Also works with collections and arrays. {@link Flattison Flattison} classes require No-Args constructor. Fields that can not
	 * be written due to type conflicts (i.e. Interfaces or Enums), will use regular setters (methods with names starting with "set", followed by field name, case-insensitive) or methods marked as {@link Deserializer Deserializer}. Unresolved conflicts or
	 * missing values will be skipped, as well as fields marked with {@link Skipson Skipson}.
	 *
	 * @param json
	 *        {@link JSONObject}, {@link JSONArray} or object of class marked with {@link Flattison Flattison}. Otherwise, this function returns object as it is;
	 * @param clazz
	 * 		Class of the provided json object to be deserialized into;
	 * @param innerTypes
	 * 		If the provided json object is a collection, this parameter is mandatory to determine the classes of the child elements, otherwise it is determined automatically.
	 */
	public Object into(Object json, Class<?> clazz, Type... innerTypes) {
		
		if (json instanceof JSONArray jsonArray) {
			// value is a collection of List or Set
			
			val collection = Set.class.isAssignableFrom(clazz) ? new LinkedHashSet<>() : new LinkedList<>();
			val types = innerTypes.length == 1 ? determineTypes(innerTypes[0]) : determineTypes(null);
			
			for (var element : jsonArray) collection.add(into(element, types.getValue0(), types.getValue1()));
			return clazz.isArray() ? collection.toArray((Object[]) Array.newInstance(clazz.getComponentType(), collection.size())) : collection;
			
		} else if (json instanceof JSONObject jsonObject) {
			if (jsonObject.length() == 1 && jsonObject.has("list")) {
				// value is serialized list or array
				return into(jsonObject.get("list"), clazz, innerTypes);
			} else if (Map.class.isAssignableFrom(clazz)) {
				// value is a declared map
				
				val map = new HashMap<>();
				val types = innerTypes.length == 2 ? determineTypes(innerTypes[1]) : determineTypes(null);
				
				for (var element : jsonObject.keySet())
					map.put(element, into(jsonObject.get(element), types.getValue0(), types.getValue1()));
				
				return map;
				
			} else if (clazz.isAnnotationPresent(Flattison.class)) {
				// value is declared serializable object
				
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
							      log.info("[JSON Deserialization] Value for the key not found in JSON source. Case-sensitive! Skipped field: %s".formatted(name));
						      } catch (IllegalArgumentException e) {
							      val deserializer = deserializers.get(name.toLowerCase());
							      if (deserializer != null) {
								      try {
									      // method is setter (void)
									      deserializer.invoke(result, value);
								      } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException x) {
									      log.warn("[JSON Deserialization] Value can not be written to field of type %s. Check for missing @Deserializer or Setter method. Case-insensitive! Skipped field: %s".formatted(type.getSimpleName(), name));
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
	 * Splits given class type into raw class and type arguments.
	 *
	 * @return Tuple of Class and populated Type[] if any, empty otherwise. I.e. {@code List<Map<String,Set>>} returns {@code Pair<List, Map<String,Set>>}.
	 */
	private Pair<Class<?>, Type[]> determineTypes(Type elementType) {
		Class<?> elementClass = Objects.class;
		var arguments = new Type[0];
		
		if (elementType == null) return Pair.with(elementClass, arguments);
		
		try {
			if (elementType instanceof ParameterizedType t) {
				elementClass = Class.forName(t.getRawType().getTypeName());
				arguments = t.getActualTypeArguments();
			} else {
				elementClass = Class.forName(elementType.getTypeName());
			}
			
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
		return Pair.with(elementClass, arguments);
	}
	
	/**
	 * Mark class that will be (de-)serialized to/from JSON using fields values directly and recursively - for {@link Flattison Flattison} objects and collections members.
	 */
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Flattison {
	
	}
	
	/**
	 * Mark fields to be skipped in (de-)serializations.
	 */
	@Target(ElementType.FIELD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Skipson {
	
	}
	
	/**
	 * Mark setter method to be used in deserialization for the given field. Usable for fields of type other than {@link Map}, {@link Collection} types or {@link Flattison Flattison} class and outside primitive scope. I.e. Interfaces and Enums.
	 */
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Deserializer {
		
		String fieldName();
		
	}
	
}