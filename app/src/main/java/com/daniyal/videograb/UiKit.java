package com.daniyal.videograb;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;
import java.util.Locale;

public final class UiKit {
    private UiKit(){}
    public static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static TextView text(Context c,String s,float sp,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(sp);t.setTextColor(ContextCompat.getColor(c,color));return t;}
    public static TextView title(Context c,String s,float sp){TextView t=text(c,s,sp,R.color.vg_text);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    public static TextView muted(Context c,String s,float sp){return text(c,s,sp,R.color.vg_text_muted);}
    public static MaterialCardView card(Context c){MaterialCardView card=new MaterialCardView(c);card.setCardBackgroundColor(ContextCompat.getColor(c,R.color.vg_surface));card.setRadius(dp(c,18));card.setCardElevation(dp(c,1));card.setStrokeWidth(dp(c,1));card.setStrokeColor(ContextCompat.getColor(c,R.color.vg_outline));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(c,12));card.setLayoutParams(lp);return card;}
    public static TextView badge(Context c,String s,int colorRes){TextView t=text(c,s,11,R.color.vg_on_primary);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.CENTER);t.setPadding(dp(c,9),dp(c,4),dp(c,9),dp(c,4));GradientDrawable g=new GradientDrawable();g.setColor(ContextCompat.getColor(c,colorRes));g.setCornerRadius(dp(c,20));t.setBackground(g);return t;}
    public static String bytes(long n){if(n<0)return"—";if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.US,"%.1f KB",n/1024.0);if(n<1073741824)return String.format(Locale.US,"%.1f MB",n/1048576.0);return String.format(Locale.US,"%.2f GB",n/1073741824.0);}
    public static String speed(long n){return n<=0?"":bytes(n)+"/s";}
    public static String duration(double sec){if(sec<=0)return"";int s=(int)Math.round(sec);return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,(s%3600)/60,s%60):String.format(Locale.US,"%d:%02d",s/60,s%60);}
    public static void gone(View v,boolean gone){v.setVisibility(gone?View.GONE:View.VISIBLE);}
}
