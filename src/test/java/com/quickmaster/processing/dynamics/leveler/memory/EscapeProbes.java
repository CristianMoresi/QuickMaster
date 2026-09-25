package com.quickmaster.processing.dynamics.leveler.memory;

import java.lang.ref.Reference;
import java.util.*;

/** Quiescent baseline probes; identities are compared directly, not identity hash codes. */
final class EscapeProbes
{
    record ThreadCapture(Thread.State state,IdentityHashMap<Object,Object> locals,IdentityHashMap<Object,Object> inherited) { }
    record Capture(IdentityHashMap<Thread,ThreadCapture> threads,List<Object[]> queues,Object sentinel) { }
    record Difference(Map<String,Object> forbiddenRoots,List<String> violations) { }
    private EscapeProbes() { }
    static Capture capture(List<? extends Collection<?>> queues)
    {
        IdentityHashMap<Thread,ThreadCapture> threads=new IdentityHashMap<>();
        for(Thread thread:Thread.getAllStackTraces().keySet())
            threads.put(thread,new ThreadCapture(thread.getState(),locals(thread,"threadLocals"),locals(thread,"inheritableThreadLocals")));
        List<Object[]> snapshots=new ArrayList<>(); for(Collection<?> queue:queues)snapshots.add(queue.toArray());
        return new Capture(threads,List.copyOf(snapshots),HarnessEscapeSentinel.value);
    }
    private static IdentityHashMap<Object,Object> locals(Thread thread,String field)
    {
        IdentityHashMap<Object,Object> result=new IdentityHashMap<>();Object map=MemoryAccess.get(thread,field);
        if(map==null)return result;
        Object[] table=(Object[])MemoryAccess.get(map,"table");
        for(Object entry:table)if(entry!=null)result.put(((Reference<?>)entry).get(),MemoryAccess.get(entry,"value"));
        return result;
    }
    static Difference compare(Capture before,Capture after,Set<String> disabled)
    {
        Map<String,Object> roots=new LinkedHashMap<>();List<String> violations=new ArrayList<>();
        if(before.sentinel!=null)violations.add("BASELINE_SENTINEL_NOT_NULL");
        List<Thread> ordered=new ArrayList<>(after.threads.keySet());ordered.sort(Comparator.comparingLong(Thread::getId));
        for(Thread thread:ordered)
        {
            ThreadCapture current=after.threads.get(thread),old=before.threads.get(thread);
            if(old==null)
            {
                if(!disabled.contains("threads")){violations.add("NEW_LIVE_THREAD");roots.put("external.thread["+thread.getId()+"]",thread);}
                continue;
            }
            if(!disabled.contains("threadLocal"))diffMap(old.locals,current.locals,"external.threadLocal["+thread.getId()+"]",roots,violations);
            if(!disabled.contains("inheritableThreadLocal"))diffMap(old.inherited,current.inherited,"external.inheritableThreadLocal["+thread.getId()+"]",roots,violations);
        }
        if(!disabled.contains("queues"))
        {
            if(before.queues.size()!=after.queues.size())violations.add("QUEUE_REGISTRY_CHANGED");
            for(int i=0;i<after.queues.size();i++)
            {
                Object[] a=after.queues.get(i),b=i<before.queues.size()?before.queues.get(i):new Object[0];
                for(int j=0;j<a.length;j++)if(j>=b.length || a[j]!=b[j])
                {violations.add("QUEUE_ELEMENT_CHANGED");roots.put("external.queue["+i+"]["+j+"]",a[j]);}
                if(a.length<b.length)violations.add("QUEUE_ELEMENT_REMOVED");
            }
        }
        if(!disabled.contains("sentinel") && after.sentinel!=before.sentinel)
        {violations.add("SENTINEL_CHANGED");if(after.sentinel!=null)roots.put("external.sentinel",after.sentinel);}
        return new Difference(Collections.unmodifiableMap(roots),List.copyOf(violations));
    }
    private static void diffMap(IdentityHashMap<Object,Object> before,IdentityHashMap<Object,Object> after,String path,Map<String,Object> roots,List<String> violations)
    {
        int ordinal=0;
        for(var e:after.entrySet())
        {
            if(!before.containsKey(e.getKey()) || before.get(e.getKey())!=e.getValue())
            {violations.add("THREAD_LOCAL_CHANGED");if(e.getValue()!=null)roots.put(path+"["+(ordinal++)+"]",e.getValue());}
        }
        for(Object key:before.keySet())if(!after.containsKey(key))violations.add("THREAD_LOCAL_REMOVED");
    }
}
