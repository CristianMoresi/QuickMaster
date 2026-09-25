package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Independently compiled test neighbors; never replaces accepted product classes or schemas. */
record HannMutant(URLClassLoader loader, Object instance) implements AutoCloseable
{
    static String source(String owner) throws Exception
    {
        return Files.readString(Path.of(System.getProperty("qm.activeSources", "src/main/java"),
                "com/quickmaster/processing/dynamics/leveler/" + owner + ".java"));
    }

    static String replaceOnce(String source, String from, String to)
    {
        assertTrue(source.contains(from), "Missing mutant anchor " + from);
        assertEquals(source.indexOf(from), source.lastIndexOf(from), "Ambiguous mutant anchor " + from);
        assertNotEquals(from, to);
        return source.replace(from, to);
    }

    static HannMutant compile(String originalOwner, String mutantOwner, String source) throws Exception
    {
        String binaryName = "com.quickmaster.processing.dynamics.leveler." + mutantOwner;
        String changed = replaceOnce(source, "public final class " + originalOwner,
                "public final class " + mutantOwner);
        var compiled = M004ClassfileFixtures.compile(binaryName, changed);
        URLClassLoader loader = new URLClassLoader(new java.net.URL[] { compiled.root().toUri().toURL() },
                HannMutant.class.getClassLoader());
        try
        {
            Object instance = loader.loadClass(binaryName).getConstructor().newInstance();
            System.out.println("HANN_MUTANT " + mutantOwner + " sha256=" + AsyncEscapeBytecodeGuard.sha(compiled.bytes()));
            return new HannMutant(loader, instance);
        }
        catch (Exception | Error failure)
        {
            loader.close();
            throw failure;
        }
    }

    Object extract(Object... arguments)
    {
        try
        {
            for (var method : instance.getClass().getDeclaredMethods())
                if (method.getName().equals("extract")) return method.invoke(instance, arguments);
            throw new AssertionError("No extract method on mutant");
        }
        catch (ReflectiveOperationException failure)
        {
            throw new IllegalStateException("Mutant invocation failed before its semantic oracle", failure);
        }
    }

    @Override public void close() throws Exception { loader.close(); }
}
