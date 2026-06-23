package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

/**
 * Xposed helper methods stub for compilation purposes.
 * These are compileOnly stubs -- method bodies throw UnsupportedOperationException at runtime.
 */
public class XposedHelpers {

    private XposedHelpers() {}

    /**
     * Finds a class by name using the given class loader.
     */
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds a class by name using the given class loader, returning null if not found.
     */
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds and hooks a method.
     */
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds and hooks a method.
     */
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds and hooks a constructor.
     */
    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader classLoader,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds and hooks a constructor.
     */
    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Calls an instance method on an object.
     */
    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Calls a static method.
     */
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Calls a static method by class name.
     */
    public static Object callStaticMethod(String className, ClassLoader classLoader,
            String methodName, Object... args) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Creates a new instance using the given constructor.
     */
    public static Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Creates a new instance using the default constructor.
     */
    public static Object newInstance(Class<?> clazz) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Gets the value of an instance field.
     */
    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Gets the value of a static field.
     */
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Gets the value of a static field by class name.
     */
    public static Object getStaticObjectField(String className, ClassLoader classLoader, String fieldName) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Gets the value of an int field.
     */
    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Sets the value of an instance field.
     */
    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Sets the value of a static field.
     */
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Sets the value of a static field by class name.
     */
    public static void setStaticObjectField(String className, ClassLoader classLoader,
            String fieldName, Object value) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Sets the value of an int field.
     */
    public static void setIntField(Object obj, String fieldName, int value) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Sets the value of a boolean field.
     */
    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Gets the value of a boolean field.
     */
    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds a field by name, traversing the class hierarchy.
     */
    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds a field by type, traversing the class hierarchy.
     */
    public static Field findFieldByType(Class<?> clazz, Class<?> fieldType) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds a method by name and parameter types, traversing the class hierarchy.
     */
    public static Method findMethodExact(String className, ClassLoader classLoader,
            String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds a method by name and parameter types, traversing the class hierarchy.
     */
    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds the best-matching method by name and parameter types.
     */
    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }

    /**
     * Finds the best-matching constructor by parameter types.
     */
    public static Constructor<?> findConstructorBestMatch(Class<?> clazz, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("XposedHelpers is a compile-only stub");
    }
}
