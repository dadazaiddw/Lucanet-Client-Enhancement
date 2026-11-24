package cn.lucanet.enhancement.exit;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** A daemon checks live preferences; inactive hot-update modules release their worker. */
final class StorageMaintenance implements Runnable {
    private final BooleanSupplier current;
    private final ScheduledExecutorService worker;
    private final Thread shutdown;
    private final Runnable stopped;
    private String previous="";
    private long nextCleanup;
    private StorageMaintenance(BooleanSupplier current,Runnable stopped){
        this.current=current;this.stopped=stopped;
        worker=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"Lucanet-storage-retention");thread.setDaemon(true);thread.setContextClassLoader(null);return thread;});
        shutdown=new Thread(()->{if(current.getAsBoolean()){maintain(true);EnhancementLog.info("AGENT_STOPPED");}},"Lucanet-storage-exit");
        shutdown.setContextClassLoader(null);
    }
    static void start(BooleanSupplier current,Runnable stopped){
        StorageMaintenance maintenance=new StorageMaintenance(current,stopped);
        try{Runtime.getRuntime().addShutdownHook(maintenance.shutdown);maintenance.worker.scheduleWithFixedDelay(maintenance,2,2,TimeUnit.SECONDS);}
        catch(RuntimeException unavailable){maintenance.worker.shutdown();stopped.run();}
    }
    @Override public void run(){
        if(!current.getAsBoolean()){
            worker.shutdown();try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException ignored){}stopped.run();return;
        }
        maintain(false);
    }
    private synchronized void maintain(boolean force){
        try{
            FeatureSettings.StoragePreferences archive=FeatureSettings.archive(),log=FeatureSettings.logs();
            String state=archive.enabled+"|"+archive.directory+"|"+archive.days+"|"+log.enabled+"|"+log.directory+"|"+log.days;
            long now=System.nanoTime();if(!force&&state.equals(previous)&&now<nextCleanup)return;
            if(archive.enabled)StorageRetention.archives(archive.directory,archive.days);
            if(log.enabled)StorageRetention.logs(log.directory,log.days);
            previous=state;nextCleanup=now+60_000_000_000L;
        }catch(RuntimeException failure){System.err.println("[lucanet-enhancement] Storage maintenance unavailable: "+failure);}
    }
}
