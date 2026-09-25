package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class ConformanceBindingRemoteTest
{
    @Test
    void remoteAndHostedInnerJarUrlsAreRejectedBeforeAnyJarOpen() throws Exception
    {
        for (String inner : new String[] {"https://invalid.example/conformance.jar", "file://remote-host/conformance.jar"})
        {
            int[] opens = new int[1];
            Path classes = Path.of(ConformanceCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            URL fake = new URL(null, "jar:" + inner + "!/com/quickmaster/processing/dynamics/leveler/ConformanceArtifactLoader.class",
                    new URLStreamHandler()
                    {
                        @Override protected URLConnection openConnection(URL url) throws IOException
                        {
                            return new JarURLConnection(url)
                            {
                                @Override public void connect() { throw new AssertionError("No remote connect allowed."); }
                                @Override public JarFile getJarFile()
                                {
                                    opens[0]++;
                                    throw new AssertionError("The inner URL must be rejected before getJarFile.");
                                }
                            };
                        }
                    });
            ClassLoader isolated = new ClassLoader(ClassLoader.getPlatformClassLoader())
            {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException
                {
                    try
                    {
                        byte[] bytes = Files.readAllBytes(classes.resolve(name.replace('.', '/') + ".class"));
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                    catch (IOException ex) { throw new ClassNotFoundException(name, ex); }
                }
                @Override public URL getResource(String name)
                {
                    return name.equals("com/quickmaster/processing/dynamics/leveler/ConformanceArtifactLoader.class") ? fake : null;
                }
            };
            Class<?> type = Class.forName("com.quickmaster.processing.dynamics.leveler.ConformanceArtifactLoader", true, isolated);
            var read = type.getDeclaredMethod("loadCurrent"); read.setAccessible(true);
            Object report = read.invoke(null);
            assertEquals("FAILED", report.getClass().getMethod("state").invoke(report).toString());
            var binding = type.getDeclaredMethod("currentBuildBinding"); binding.setAccessible(true);
            assertNull(binding.invoke(null));
            assertEquals(0, opens[0]);
        }
    }
}
