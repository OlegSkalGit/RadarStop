# ProGuard / R8 optimization rules for Radar Detector Ultra-Light MVP
-keepclasseswithmembers class * extends android.database.sqlite.SQLiteOpenHelper {
    public <init>(...);
}
