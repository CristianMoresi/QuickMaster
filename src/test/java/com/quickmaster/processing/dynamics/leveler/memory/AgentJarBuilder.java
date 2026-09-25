package com.quickmaster.processing.dynamics.leveler.memory;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

/** One-class agent, built in evidence outside product/package trees before spawning any child. */
final class AgentJarBuilder
{
    private AgentJarBuilder() { }
    static Path create(Path testClasses,Path output) throws Exception
    {
        String path=ShadowReachabilityAgent.class.getName().replace('.','/')+".class";
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Premain-Class",ShadowReachabilityAgent.class.getName());
        try(var out=new JarOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW),manifest))
        {
            JarEntry entry=new JarEntry(path);entry.setTime(0);out.putNextEntry(entry);Files.copy(testClasses.resolve(path),out);out.closeEntry();
        }
        try(var jar=new JarFile(output.toFile()))
        {
            if(jar.size()!=2 || jar.getJarEntry(path)==null || jar.getJarEntry("META-INF/MANIFEST.MF")==null)
                throw new AssertionError("AGENT_JAR_CONTENTS");
        }
        return output.toAbsolutePath();
    }
}
