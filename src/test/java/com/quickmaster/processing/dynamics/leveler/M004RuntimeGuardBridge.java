package com.quickmaster.processing.dynamics.leveler;

import java.nio.file.Path;
import java.util.*;
import com.google.gson.*;

/** Read-only test bridge; delegates to the frozen guard, never reproduces its policy. */
public final class M004RuntimeGuardBridge
{
    private M004RuntimeGuardBridge() { }
    public static JsonObject scan(Path classes) throws Exception
    {
        var result = AsyncEscapeBytecodeGuard.scan(classes);
        JsonObject json = new Gson().toJsonTree(result).getAsJsonObject();
        json.addProperty("passed", result.passed());
        return json;
    }
    public static JsonArray inspect(byte[] bytes)
    { return new Gson().toJsonTree(AsyncEscapeBytecodeGuard.inspect(bytes)).getAsJsonArray(); }
    public static JsonObject scanActive(Path classes) throws Exception {
        var result=AsyncEscapeBytecodeGuard.scanActive(classes);
        JsonObject json=new Gson().toJsonTree(result).getAsJsonObject();json.addProperty("passed",result.passed());return json;
    }
    public static List<String> activeOwners() { return ActiveLevelerGuardContract.owners().stream().sorted().toList(); }
    public static List<String> activeEnumNames(String owner) { return ActiveLevelerGuardContract.enumNames(owner); }
    /** Literal classfile constants after the active guard passes; loaded resources must match those exact bytes. */
    public static JsonArray staticFields(Path classes) throws Exception {
        JsonArray result=new JsonArray();
        for(String owner:activeOwners()) {
            byte[] bytes=java.nio.file.Files.readAllBytes(classes.resolve(owner+".class"));
            try(var loaded=M004RuntimeGuardBridge.class.getClassLoader().getResourceAsStream(owner+".class")) {
                if(loaded==null||!java.util.Arrays.equals(bytes,loaded.readAllBytes()))throw new AssertionError("LOADED_CLASS_BINDING:"+owner);
            }
            M004Classfile file=M004Classfile.parse(bytes);
            for(var field:file.fields)if((field.access()&8)!=0) {
                JsonObject row=new JsonObject();row.addProperty("owner",owner);row.addProperty("name",field.name());row.addProperty("descriptor",field.descriptor());row.addProperty("access",field.access());
                if(field.constantIndex()!=0) {
                    var cp=file.pool[field.constantIndex()];String value=switch(cp.tag()) {
                        case 3->"I:"+((Long)cp.value()).intValue();case 4->"F:"+Long.toUnsignedString((Long)cp.value(),16);
                        case 5->"J:"+cp.value();case 6->"D:"+Long.toUnsignedString((Long)cp.value(),16);case 8->"S:"+file.string(field.constantIndex());default->throw new AssertionError("STATIC_CONSTANT_TYPE");};
                    row.addProperty("constant",value);
                }
                result.add(row);
            }
        }
        return result;
    }
    public static List<String> owners()
    {
        Set<String> result = new TreeSet<>(M004BytecodePolicy.SCANNED_M004_CLASSFILES);
        result.addAll(M004BytecodePolicy.BOUNDARIES.keySet());
        return List.copyOf(result);
    }
}
