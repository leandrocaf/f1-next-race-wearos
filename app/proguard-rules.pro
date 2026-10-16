# WorkManager/Room instanciam estas classes por reflexão; o R8 removia os construtores
# e o app caía ao iniciar (NoSuchMethodException: WorkDatabase_Impl.<init>)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
