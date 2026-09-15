package com.daniyal.videograb;

import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

public final class AppPrefs {
    private static final String PREF="vg_v5_prefs";
    private static final String HIDE_SMALL="hide_small_media";
    private static final String THEME="theme";
    private static final String MP3="mp3_bitrate";
    private AppPrefs(){}
    public static boolean hideSmall(Context c){return c.getSharedPreferences(PREF,0).getBoolean(HIDE_SMALL,true);}
    public static void setHideSmall(Context c,boolean v){c.getSharedPreferences(PREF,0).edit().putBoolean(HIDE_SMALL,v).apply();}
    public static String theme(Context c){return c.getSharedPreferences(PREF,0).getString(THEME,"system");}
    public static void setTheme(Context c,String v){c.getSharedPreferences(PREF,0).edit().putString(THEME,v).apply();applyTheme(v);}
    public static int mp3(Context c){return c.getSharedPreferences(PREF,0).getInt(MP3,192);}
    public static void setMp3(Context c,int v){c.getSharedPreferences(PREF,0).edit().putInt(MP3,v).apply();}
    public static void applyTheme(Context c){applyTheme(theme(c));}
    private static void applyTheme(String v){
        if("light".equals(v))AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if("dark".equals(v))AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
