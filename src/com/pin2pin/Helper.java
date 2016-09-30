package com.pin2pin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * Created by mint on 9/29/16.
 */
public class Helper {
    public static int TIME_POLITENESS = 2000;
    public static int TIMEOUT_WEBSITE = 60000 * 2;
    public static int MAX_BODY_SIZE = Integer.MAX_VALUE;
    public static String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36";

    public static String getTimeStamp(){
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Calendar.getInstance().getTime());
    }

    public static Document downloadHtmlDocument(String url) throws Exception{

        Thread.sleep(Helper.TIME_POLITENESS);

        return Jsoup.connect(url).maxBodySize(Helper.MAX_BODY_SIZE).userAgent(Helper.USER_AGENT).timeout(Helper.TIMEOUT_WEBSITE).get();
    }

    public static String downloadTextFile(String url) throws Exception{

        Thread.sleep(Helper.TIME_POLITENESS);

        // The download CSV is in UTF-8 with BOM (0xEF, 0xBB, 0xBF)

        //return Jsoup.connect(url).timeout(Helper.TIMEOUT_WEBSITE).userAgent(Helper.USER_AGENT).ignoreContentType(true).execute().body();
        return removeUTF8BOM(new String(Jsoup.connect(url).timeout(Helper.TIMEOUT_WEBSITE).userAgent(Helper.USER_AGENT).ignoreContentType(true).execute().bodyAsBytes(), Charset.forName("UTF-8")));
    }

    // http://stackoverflow.com/questions/21891578/removing-bom-characters-using-java
    public static final String UTF8_BOM = "\uFEFF";

    private static String removeUTF8BOM(String s) {
        if (s.startsWith(UTF8_BOM)) {
            s = s.substring(1);
        }
        return s;
    }
}
