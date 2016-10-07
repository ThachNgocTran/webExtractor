package com.pin2pin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
    private static String FILEPATH_PROBLEM = "Problem.txt";
    public static String FILEPATH_TEMP_OBJECT = "tempobj.ser";

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

    public static String makeAbsoluteUrl(String base, String relative) throws Exception{
        URL hh = new URL(new URL(base), relative);
        return hh.toString();
    }

    public static void logProblem(String str) throws Exception{
        BufferedWriter writer = Files.newBufferedWriter(Paths.get(FILEPATH_PROBLEM), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writer.write(String.format("%s;%s\n", Helper.getTimeStamp(), str));
        writer.flush();
        writer.close();
    }

    // http://www.mkyong.com/java/how-to-read-an-object-from-file-in-java/
    public static void saveObjectToFile(Object obj, String path) throws Exception{

        if (Files.exists(Paths.get(path))){
            Files.delete(Paths.get(path));
        }

        FileOutputStream fout = new FileOutputStream(path);
        ObjectOutputStream oos = new ObjectOutputStream(fout);
        oos.writeObject(obj);
        oos.close();
    }

    //http://www.mkyong.com/java/how-to-read-an-object-from-file-in-java/
    // It's the responsibility of the reader to cast the result into an appropriate object.
    public static Object loadObjectFromFile(String path) throws Exception{
        FileInputStream fin = new FileInputStream(path);
        ObjectInputStream ois = new ObjectInputStream(fin);
        Object ob = ois.readObject();
        ois.close();
        return ob;
    }

    public static boolean checkStringGoodForCSVFormat(String strInput){

        boolean res = true;
        if (strInput.contains(",") ||
                strInput.contains("\"")){
            res = false;
        }

        return res;
    }

    public static String makeStringGoodForCSVFormat(String strInput){

        //if (!checkStringGoodForCSVFormat(strInput)){
            return "\"" + strInput.replace("\"", "\"\"") + "\"";
        //}

        //return strInput;
    }

    // http://stackoverflow.com/questions/6198986/how-can-i-replace-non-printable-unicode-characters-in-java
    //public static String removeNonPrintableCharacters(String strInput){
        //return strInput.replaceAll("\\p{C}", "");
    //    return strInput.replaceAll("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]", "?");
    //}

    public static String removeAnnoyingCharacters(String strInput){
        strInput = strInput.replace("\u00A0", "");

        return strInput;
    }

    public static void countLines(String folderPath) throws Exception{
        File folder = new File(folderPath);
        File[] listOfFiles = folder.listFiles();

        int totalLines = 0;
        int totalFiles = 0;

        for (int i = 0; i < listOfFiles.length; i++) {
            if (listOfFiles[i].isFile()) {
                String fileName = listOfFiles[i].getName();
                BufferedReader reader = new BufferedReader(new FileReader(folderPath + fileName));
                int lines = 0;
                while (reader.readLine() != null) lines++;
                reader.close();

                totalLines += lines;
                totalFiles++;
            }
        }

        System.out.println(String.format("totalLines = %d; totalFiles = %d", totalLines, totalFiles));
    }
}

/*
        String base = "http://eu.mouser.com/Electronic-Components/?";
        String relative = "../Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/_/N-ax1ma/";

        URL hh = new URL(new URL(base), relative);
        System.out.println(hh.toString());

        String relative2 = "./Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/_/N-ax1ma/";
        URL hh2 = new URL(new URL(base), relative2);
        System.out.println(hh2.toString());

        String relative3 = "/Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/_/N-ax1ma/";
        URL hh3 = new URL(new URL(base), relative3);
        System.out.println(hh3.toString());

        ------------------------------------------------------------------------------

        http://eu.mouser.com/Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/_/N-ax1ma/
        http://eu.mouser.com/Electronic-Components/Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/_/N-ax1ma/
        http://eu.mouser.com/Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/_/N-ax1ma/

 */

