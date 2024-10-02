package krystal;

import krystal.Skip.SkipTypes;
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
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class wrapping {@link JSONObject} - utilities for deep (de)serialization of objects.
 *
 * @apiNote {@link Enum Enums} and {@link Collection Collections} require {@link Deserializer}!
 * @see #fromObject(Object)
 * @see #into(Object, Class, Type...)
 */
@UtilityClass
@Log4j2
public class JSON {
	
	/**
	 * Serialize Object into {@link JSONObject}. Fields marked with {@link Skip @Skip} will be skipped.
	 *
	 * @param obj
	 *        {@link Map}, object of class marked with {@link Flattison @Flattison} or regular object to be serialized with {@link JSONObject#JSONObject(Object)} constructor.
	 * @return If null obj provided - will return empty {@link JSONObject}.
	 */
	public JSONObject fromObject(Object obj) {
		if (obj == null) return new JSONObject();
		val flattison = flattison(obj);
		if (Map.class.isAssignableFrom(flattison.getClass())) {
			return new JSONObject((Map<?, ?>) flattison);
		} else {
			return new JSONObject(flattison);
		}
	}
	
	public JSONObject fromObject(Object obj, String... fields) {
		return new JSONObject(fromObject(obj), fields);
	}
	
	/**
	 * Serialize Iterable, Collection or Array into {@link JSONArray}.
	 *
	 * @param arrayOrCollection
	 *        {@link Collection}, {@link Iterable} or {@link Array} of objects. Performs serialization of child elements.
	 * @return If null obj provided - will return empty {@link JSONArray}.
	 */
	public JSONArray fromObjects(Object arrayOrCollection) {
		if (arrayOrCollection == null) return new JSONArray();
		val flattison = flattison(arrayOrCollection);
		val flattisonClass = flattison.getClass();
		if (Collection.class.isAssignableFrom(flattisonClass)) {
			return new JSONArray((Collection<?>) flattison);
		} else if (Iterable.class.isAssignableFrom(flattisonClass)) {
			return new JSONArray((Iterable<?>) flattison);
		} else {
			return new JSONArray(flattison);
		}
	}
	
	/**
	 * Recursively prep object and its fields for serialization by changing it into Map or Collection.
	 */
	private Object flattison(Object obj) {
		if (obj == null) return null;
		val clazz = obj.getClass();
		
		if (Map.class.isAssignableFrom(clazz)) {
			val flattisonMap = new LinkedHashMap<>(); // nullable values
			((Map<?, ?>) obj).forEach((k, v) -> flattisonMap.put(k, flattison(v)));
			return flattisonMap;
		} else if (Collection.class.isAssignableFrom(clazz) || clazz.isArray()) {
			return (clazz.isArray() ? Stream.of((Object[]) obj) : ((Collection<?>) obj).stream())
					       .map(JSON::flattison)
					       .toList();
		} else if (clazz.isAnnotationPresent(Flattison.class)) {
			val fieldsMap = new LinkedHashMap<>(); // nullable values
			
			Stream.of(clazz.getDeclaredFields())
			      .forEach(f -> {
				      if (Tools.isSkipped(f, SkipTypes.json)) return;
				      
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
	 * Use this function to deserialize JSON object into provided {@link Flattison @Flattison} class and recursively it's members. Also works with collections and arrays. {@link Flattison @Flattison} classes require No-Args constructor. Fields, that can
	 * not be written due to type conflicts (i.e. Interfaces or Enums), will use regular setters (with argument: Object - methods with names starting with "set", followed by field name, case-insensitive) or methods marked as
	 * {@link Deserializer @Deserializer}. Unresolved conflicts or missing values will be skipped, as well as fields marked with {@link Skip @Skip}.
	 *
	 * @param fromJson
	 *        {@link JSONObject}, {@link JSONArray} or object of class marked with {@link Flattison @Flattison}. Otherwise, this function returns object as it is;
	 * @param clazz
	 * 		Class of the provided fromJson object to be deserialized into;
	 * @param innerTypes
	 * 		If the provided fromJson object is a collection, this parameter is mandatory to determine the classes of the child elements. In other cases, can be skipped.
	 */
	public Object into(Object fromJson, Class<?> clazz, Type... innerTypes) {
		
		if (fromJson instanceof JSONArray jsonArray) {
			// fromJson is a collection or array
			val collection = Set.class.isAssignableFrom(clazz) ? new LinkedHashSet<>() : new LinkedList<>();
			val types = innerTypes.length == 1 ? Tools.determineParameterTypes(innerTypes[0]) : Tools.determineParameterTypes(null);
			
			for (var element : jsonArray) collection.add(into(element, types.clazz(), types.types()));
			return clazz.isArray() ? collection.toArray((Object[]) Array.newInstance(clazz.getComponentType(), collection.size())) : collection;
		} else if (fromJson instanceof JSONObject jsonObject) {
			if (Map.class.isAssignableFrom(clazz)) {
				// fromJson is a map
				
				val map = new LinkedHashMap<>();
				val types = innerTypes.length == 2 ? Tools.determineParameterTypes(innerTypes[1]) : Tools.determineParameterTypes(null);
				
				for (var element : jsonObject.keySet())
					map.put(element, into(jsonObject.get(element), types.clazz(), types.types()));
				
				return map;
				
			} else if (clazz.isAnnotationPresent(Flattison.class)) {
				// fromJson is serializable object
				
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
										      } catch (NullPointerException _) {
											      try {
												      name = name.substring(3);
											      } catch (IndexOutOfBoundsException _) {
											      }
										      }
										      if (name.isEmpty()) name = m.getName();
										      return name.toLowerCase();
									      },
									      m -> m,
									      (a, b) -> b.isAnnotationPresent(Deserializer.class) ? b : a,
									      LinkedHashMap::new
							      ));
					
					Stream.of(clazz.getDeclaredFields())
					      .filter(f -> !Tools.isSkipped(f, SkipTypes.json) && f.trySetAccessible())
					      .forEach(f -> {
						      val name = f.getName();
						      val type = f.getType();
						      val arguments = f.getGenericType() instanceof ParameterizedType t ? t.getActualTypeArguments() : new Type[0];
						      
						      Object value = null;
						      try {
							      value = jsonObject.get(name);
							      value = into(value, type, arguments);
							      f.set(result, value);
						      } catch (JSONException _) {
							      log.info("[JSON Deserialization] Value for the key not found in JSON source. Case-sensitive! Skipped field: %s".formatted(name));
						      } catch (IllegalArgumentException _) {
							      val deserializer = deserializers.get(name.toLowerCase());
							      if (deserializer != null) {
								      try {
									      // method is setter (void)
									      deserializer.invoke(result, value);
								      } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
									      log.warn("[JSON Deserialization] Value can not be written to field of type %s. Check for missing @Deserializer or Setter method. Case-insensitive! Skipped field: %s".formatted(type.getSimpleName(), name), e);
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
			// fromJson is just a regular value of type clazz
			return fromJson;
		}
		
		return null;
	}
	
	/**
	 * With a constructor marked as {@link Deserializer} and having a single argument of {@link JSONObject}, deserialize into given class.
	 */
	@SuppressWarnings("unchecked")
	public <T> T into(Class<T> into, JSONObject from) {
		return Arrays.stream(into.getDeclaredConstructors())
		             .filter(c -> c.trySetAccessible() && (c.isAnnotationPresent(Deserializer.class)) && c.getParameterCount() == 1)
		             .filter(c -> c.getParameterTypes()[0].equals(JSONObject.class))
		             .findFirst()
		             .map(c -> {
			             try {
				             return (T) c.newInstance(from);
			             } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
				             throw new RuntimeException(e);
			             }
		             })
		             .orElseThrow();
	}
	
	/**
	 * Mark class that will be (de-)serialized to/from JSON using fields values directly and recursively - for {@link Flattison @Flattison} objects and collections members.
	 *
	 * @apiNote {@link Enum Enums} and {@link Collection Collections} require {@link Deserializer}!
	 * @see JSON
	 */
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Flattison {
	
	}
	
	/**
	 * Mark setter method to be used in deserialization for the given field. Usable for fields of type other than {@link Map}, {@link Collection} types or {@link Flattison @Flattison} class and outside primitive scope. I.e. Interfaces and
	 * <b><i>{@link Enum}</i></b>. Also, can be used to mark constructors deserializing {@link JSONObject} for {@link #into(Class, JSONObject)} method.
	 */
	@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Deserializer {
		
		String fieldName();
		
	}
	
}