package com.quickmaster.processing.dynamics.leveler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Runs the actual regression methods against an isolated product classpath. */
public final class FoundationFunctionalChild
{
    public static void main(String[] args) throws Exception
    {
        JsonArray rows = new JsonArray();
        if (args[0].equals("engine"))
        {
            for (int frames : new int[] { 47_999, 48_000 })
                rows.add(invoke("LevelerAnalysisEngineIntegrationTest",
                        "completeEngineKeepsExactExtentDespiteIdenticalFeatureCenters", frames));
        }
        else if (args[0].equals("golden")) rows.add(invoke("BoundaryDetectorTest", args[1], null));
        else if (args[0].equals("profile")) rows.add(invoke("LevelerCalibrationProfileTest", args[1], null));
        else throw new IllegalArgumentException("Unknown functional mode");
        boolean passed = true;
        for (var row : rows) passed &= row.getAsJsonObject().get("passed").getAsBoolean();
        JsonObject result = new JsonObject();
        result.addProperty("javaHome", System.getProperty("java.home"));
        result.addProperty("javaVersion", System.getProperty("java.runtime.version"));
        result.addProperty("passed", passed);
        result.add("cases", rows);
        System.out.println(result);
        if (!passed) System.exit(2);
    }

    private static JsonObject invoke(String className, String methodName, Integer argument) throws Exception
    {
        Class<?> test = Class.forName("com.quickmaster.processing.dynamics.leveler." + className);
        Object instance = test.getDeclaredConstructor().newInstance();
        Method method = argument == null ? test.getDeclaredMethod(methodName)
                : test.getDeclaredMethod(methodName, int.class);
        JsonObject result = new JsonObject();
        result.addProperty("test", className + "." + methodName);
        if (argument != null) result.addProperty("argument", argument);
        try
        {
            if (argument == null) method.invoke(instance);
            else method.invoke(instance, argument);
            result.addProperty("passed", true);
        }
        catch (InvocationTargetException error)
        {
            result.addProperty("passed", false);
            result.addProperty("failure", error.getCause().getClass().getName() + ": "
                    + error.getCause().getMessage());
        }
        return result;
    }
}
