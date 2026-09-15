package com.daniyal.videograb;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

public final class LibraryRepository {
    public static class Item { public long id,size,date; public String name,mime; public Uri uri; public boolean isAudio(){return (mime!=null&&mime.startsWith("audio/"))||name.toLowerCase().endsWith(".mp3");} }
    private LibraryRepository(){}
    public static List<Item> list(Context c){
        List<Item> out=new ArrayList<>();
        String[] p={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_MODIFIED,MediaStore.Downloads.MIME_TYPE};
        try(Cursor cur=c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,p,MediaStore.Downloads.RELATIVE_PATH+" LIKE ?",new String[]{"Download/VideoGrab%"},MediaStore.Downloads.DATE_MODIFIED+" DESC")){
            if(cur==null)return out;
            while(cur.moveToNext()){Item f=new Item();f.id=cur.getLong(0);f.name=cur.getString(1);f.size=cur.getLong(2);f.date=cur.getLong(3);f.mime=cur.getString(4);f.uri=ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,f.id);out.add(f);}
        }catch(Exception ignored){}
        return out;
    }
}
