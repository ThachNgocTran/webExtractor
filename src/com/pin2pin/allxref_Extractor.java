package com.pin2pin;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;

public class allxref_Extractor {

    // CONSTANTS
    /*
        POLITENESS POLICY:
        https://en.wikipedia.org/wiki/Web_crawler#Politeness_policy
        Some suggests 60 seconds, 10 seconds, 15 seconds, 1 second, or dynamic (10 * t). (t = time to load 1 page).
        Or "Crawl-delay:" parameter in the robots.txt ==> allxref doesn't have this field.
        Access intervals from known crawlers vary between 20 seconds and 3–4 minutes.
        My own experiment: Workplace and Home: 2 seconds (not to get IP blocked). It seems less than 1 second would get blocked.
     */
    public static int TIME_POLITENESS = 2000;

    public static String PATH_CSV = "final.csv";
    public static String PATH_RECOVERY = "Recovery.txt";   // Implication: for scraping static pages.
    public static String PATH_PROBLEMATIC_PAGE = "ProblematicPage.txt";

    public static int TIMEOUT_WEBSITE = 60000 * 2;

    /*
        RECOVERY MECHANISM:
        When ready to do something (connect to website, download html, extract data...), keep track of
        the current progress. So in case of unmanaged exception (such as permanent network failure),
        exception is raised, and the current progress is saved for later recovery.
        The mechanism is NOT aggressive, which means that if the java process is killed (machine is shutdown),
        the latest progress is not safely stored.
        There is a small risk of having some duplicate data at the end of final.csv (the current progress
        is saved, the website data of that progress is stored, then suddenly exception raises, the recovery
        mode will be restarted at that progress, leading to duplicate data).
        Recovery mode works best in case of static webpages.
     */
    public static String STAGE_1_HOMEPAGE_LAST = "";    // Blank means first run; Not_Blank means Recovery (We start RIGHT AT the last known stages)
    public static String STAGE_2_MANUPAGE_LAST = "";
    public static String STAGE_1_HOMEPAGE_LATEST = "";
    public static String STAGE_2_MANUPAGE_LATEST = "";

    public static void main(String[] args) {
        //test();
	    System.out.println("*** START ***");

        if (args != null && args.length > 0){

            TIME_POLITENESS = Integer.parseInt(args[0]);
            System.out.println(String.format("Override default TIME_POLITENESS = %d", TIME_POLITENESS));
        }

        String BASE_URL = "http://www.allxref.com";
        //String BASE_URL = "http://www.allxref.com/seiko/"; // 404 error

        try {

            if (!Files.exists(Paths.get(PATH_RECOVERY))){
                Files.deleteIfExists(Paths.get(PATH_CSV));
                saveDataToFile("PartNo,Manufacturer,Replacement,ReplacementMfr,Type,LinkToPart\n");
            } else {    // restore the stages
                restoreStages(); // we continue using the current CSV file (append it).
            }

            // For Homepage
            Document docHomepage = Jsoup.connect(BASE_URL).timeout(TIMEOUT_WEBSITE).get();

            Element listOfManu = docHomepage.getElementsByClass("complist").get(0);
            Elements listOfManuLinks = listOfManu.select("li > a[href]");

            // *** Validation ***
            if (listOfManuLinks.size() != 110)
                throw new Exception("Number of manufacturers: Invalid!");

            int countManu = 1;
            STAGE_1_HOMEPAGE_LATEST = "";

            for (Element linkManu : listOfManuLinks) {

                // debug
                //if (countManu > 2)
                //    break;

                String linkManuHref = linkManu.attr("href");
                String linkToManu = BASE_URL + linkManuHref;

                System.out.println(String.format("Checking Manufacturer [%d/110]... %s", countManu++, linkToManu));

                // Check if we are in Recovery mode.
                if (STAGE_1_HOMEPAGE_LAST != null && STAGE_1_HOMEPAGE_LAST.length() > 0){
                    // yes
                    if (STAGE_1_HOMEPAGE_LAST.equals(linkToManu)){
                        // It's the last stage needed to restore, go ahead.

                        STAGE_1_HOMEPAGE_LAST = ""; // we return to normal from now.
                    } else {
                        System.out.println(String.format("Already done. Move next."));
                        continue; // move to the next item, keep searching for the last stage.
                    }
                } else {
                    // We are NOT in the Recovery mode. Do as usual.
                    // OR we have exited the Recovery mode.
                }

                // save stage 1
                STAGE_1_HOMEPAGE_LATEST = linkToManu;

                // For Manufacturer Page
                Document docManu = null;

                try{
                    Thread.sleep(TIME_POLITENESS);

                    docManu = Jsoup.connect(linkToManu).timeout(TIMEOUT_WEBSITE).get();
                }
                catch (HttpStatusException e){  // 404 error for example; ignore and continue
                    System.out.println(e.toString());
                    docManu = null;

                    // Keep the url to the problematic page. Then continue.
                    keepProblematicPage(linkToManu);
                    continue;
                }

                if (docManu != null){
                    Elements listOfParts = docManu.select("li > b > a[href]");

                    int countPart = 1;
                    STAGE_2_MANUPAGE_LATEST = "";

                    for (Element linkPart: listOfParts){

                        String linkPartHref = linkPart.attr("href");
                        String linkToPart = BASE_URL + linkPartHref;

                        System.out.println(String.format("Checking Part. [%d/%d].. %s", countPart++, listOfParts.size(), linkToPart));

                        // Checking if we are in the Recovery mode.
                        if (STAGE_2_MANUPAGE_LAST != null && STAGE_2_MANUPAGE_LAST.length() > 0){
                            // yes
                            if (STAGE_2_MANUPAGE_LAST.equals(linkToPart)){
                                // It's the last stage needed to restore, go ahead.

                                STAGE_2_MANUPAGE_LAST = ""; // we return to normal from now.
                            } else {
                                System.out.println(String.format("Already done. Move next."));
                                continue; // move to the next item, keep searching for the last stage.
                            }
                        } else {
                            // We are NOT in the Recovery mode. Do as usual.
                            // OR we have exited the Recovery mode.
                        }

                        // save stage 2
                        STAGE_2_MANUPAGE_LATEST = linkToPart;

                        // debug
                        //if (countPart == 3){
                        //    int abc = 100;
                        //}

                        // If there is MyBusinessException, save the trouble page and go ahead.
                        String res = "";

                        try{
                            res = extractParts(linkToPart);
                        }
                        catch (MyBusinessException ex){
                            // Keep the url to the problematic page. Then continue.
                            keepProblematicPage(linkToPart);
                            continue;
                        }

                        saveDataToFile(res);

                        // if crashing here, some duplicate lines may happen in CSV when recovering.

                        //countPart++;

                    } // end of "for" listOfParts

                    STAGE_2_MANUPAGE_LATEST = "";
                } // end of "if" docManu != null (accessible)

                //countManu++;

            } // end of "for" listOfManuLinks

            // Done successfully. No need to recover
            STAGE_1_HOMEPAGE_LATEST = "";

            System.out.println("*** SUCCEED ***");
        }
        catch (Exception ex){
            System.out.println("*** Error ***\n" + ex.toString() + "\n" + ex.getMessage());
        }
        finally {

            // Keep the last stages so that they can be recovered.
            try{
                keepLastStages();
            }
            catch (Exception e) {
                System.out.println(String.format("Failed to keep last stages: %s", e.toString()));
            }
        }

        System.out.println("*** END ***");
    }

    public static String extractParts(String linkToPart) throws Exception{
        StringBuilder strBuilder = new StringBuilder();

        Document docPart = null;
        try{
            Thread.sleep(TIME_POLITENESS);

            docPart = Jsoup.connect(linkToPart).timeout(TIMEOUT_WEBSITE).get();
        }
        catch (HttpStatusException e){ // 404 error for example
            docPart = null;
            System.out.println(e.toString());

            throw new MyBusinessException("HttpStatusException: " + linkToPart);
        }

        if (docPart != null){
            Element htmlTable = docPart.select("table.mt").get(0);

            // Get PartNo and Manufacturer Name
            //String partNoAndManu = htmlTable.select("td.p").get(0).text();
            //String partNo = partNoAndManu.split(" ")[0];
            //String Manu = partNoAndManu.split(" ")[1];
            Element partNoEle = htmlTable.select("td.p").get(0);
            String partNo = partNoEle.ownText();
            String Manu = partNoEle.select("div").get(0).text();
            // Because we use CSV as storage, remove comma in the data.
            partNo = partNo.replace(",", ";");
            Manu = Manu.replace(",", ";");

            // Get ALL the parts
            Elements rows = htmlTable.select("tr");
            for (int i = 1; i < rows.size(); i++){  // ignore the first row (title row)
                Element currRow = rows.get(i);
                int index = 0;
                if (i == 1)
                    index = 1;

                Elements tdList = currRow.select("td");

                // Get Replacement
                //String replacement = currRow.select("td > a[href]").get(0).text();
                Element tdReplacement = tdList.get(index);
                String replacement = "";
                if (tdReplacement.select("a").size() > 0) { // some are a link
                    replacement = tdReplacement.select("a").get(0).text();
                }
                else{
                    // special case ==> some are plain text
                    replacement = tdReplacement.ownText();
                }
                // Because we use CSV as storage, remove comma in the data.
                replacement = replacement.replace(",", ";");

                // Get Repl. Mfr
                //String replacementMfr = currRow.select("td[align=center]:empty").get(0).text();
                //String replacementMfr = currRow.select("td[align=center]").get(0).text();
                String replacementMfr = tdList.get(index + 1).text();

                // Get Type
                //String gifUrl = currRow.select("td > img[src]").get(0).attr("src");
                String gifUrl = tdList.get(index + 2).select("img").get(0).attr("src");
                String type = "";
                if (gifUrl.equals("/img/t1.gif"))
                    type = "Pin-to-Pin Replacement";
                else if (gifUrl.equals("/img/t2.gif"))
                    type = "Compatible Equivalent";
                else if (gifUrl.equals("/img/t3.gif"))
                    type = "Functional Equivalent";
                else if (gifUrl.equals("/img/t4.gif"))
                    type = "Possible Analogue";
                else
                    throw new MyBusinessException("Type of Part: Invalid!");

                String onePart = String.format("%s,%s,%s,%s,%s,%s\n", partNo, Manu, replacement, replacementMfr, type, linkToPart);

                // *** Validation ***
                String[] columns = onePart.split(",");
                if (columns.length != 6)
                    throw new MyBusinessException(String.format("[%s]: Invalid format", onePart));
                //for (String column : columns)
                //    if (column == null || column.length() == 0)
                //        throw new Exception(String.format("[%s]: Invalid format", onePart));

                strBuilder.append(onePart);
            }
        }

        return strBuilder.toString();
    }

    // Save the data
    public static void saveDataToFile(String strToWrite) throws Exception {

        // permanently store data
        BufferedWriter writer = Files.newBufferedWriter(Paths.get(PATH_CSV), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writer.write(strToWrite);
        writer.flush();
        writer.close();

    }

    // Store the page that causes problems in extracting data for LATER investigation. Then just
    // keep moving forward with crawling.
    public static void keepProblematicPage(String url) throws Exception{

        // http://stackoverflow.com/questions/5175728/how-to-get-the-current-date-time-in-java
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());

        BufferedWriter writer = Files.newBufferedWriter(Paths.get(PATH_PROBLEMATIC_PAGE), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writer.write(String.format("%s;%s\n", timeStamp, url));
        writer.flush();
        writer.close();
    }

    // Keep the stages so that they can be recovered later if needed.
    // Some exceptions that may stop the crawling: network offline.
    public static void keepLastStages() throws Exception {

        Files.deleteIfExists(Paths.get(PATH_RECOVERY));

        if ((STAGE_1_HOMEPAGE_LATEST != null && STAGE_1_HOMEPAGE_LATEST.length() > 0) ||
                (STAGE_2_MANUPAGE_LATEST != null && STAGE_2_MANUPAGE_LATEST.length() > 0)){

            // save the stages ==> OVERWRITE any existing stages.
            BufferedWriter writer2 = Files.newBufferedWriter(Paths.get(PATH_RECOVERY), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            writer2.write(String.format("%s\n%s",
                    STAGE_1_HOMEPAGE_LATEST == null ? "" : STAGE_1_HOMEPAGE_LATEST,
                    STAGE_2_MANUPAGE_LATEST == null ? "" : STAGE_2_MANUPAGE_LATEST));
            writer2.flush();
            writer2.close();
        }

        System.out.println("Saving last known stages.");
    }

    // Restore the stages and begin the crawling FROM THERE.
    public static void restoreStages() throws Exception{

        List<String> lineArray = Files.readAllLines(Paths.get(PATH_RECOVERY), StandardCharsets.UTF_8);
        STAGE_1_HOMEPAGE_LAST = lineArray.get(0);
        STAGE_2_MANUPAGE_LAST = lineArray.get(1);

        // In Recovery mode, in the first start, the BASE_URL may not be accessible. Exception raises.
        // When keeping the stages, we use STAGE_1_HOMEPAGE_LATEST, not STAGE_1_HOMEPAGE_LAST. ==> save "blank".
        // This solution overcomes that small situation.
        STAGE_1_HOMEPAGE_LATEST = STAGE_1_HOMEPAGE_LAST;
        STAGE_2_MANUPAGE_LATEST = STAGE_2_MANUPAGE_LAST;

        System.out.println("Restoring last known stages.");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // for testing!
    public static void test(){

        try{
            // http://www.allxref.com/epson/c4.htm ==> Page not found (Part not found)
            // http://www.allxref.com/seiko/ ==> Page not found. (Manu not found)
            // http://www.allxref.com/aeroflex/ut54acts244.htm  ==> Link with Complicated "Replacement".
            // http://www.allxref.com/alsc/as7c3256axxt.htm ==> Having comma in replacement.

            // http://www.allxref.com/avx/1772000554e014.htm ==> has comma in Part.

            // http://www.allxref.com/ad/ad1582.htm ==> complex manufacturer (Analog Devices · 2.5V to 5.0V Micropower, Precision Series Mode Voltage References).
            // Not have Repl. Mfr!!!
            String str0 = extractParts("http://www.allxref.com/ad/ad1582.htm");
            System.out.println(str0);

            // http://www.allxref.com/allegro/3189.htm ==> Merely a Link.
            String str1 = extractParts("http://www.allxref.com/allegro/3189.htm");
            System.out.println(str1);

            // http://www.allxref.com/allegro/883c4001bc.htm ==> NOT a Link.
            String str2 = extractParts("http://www.allxref.com/allegro/883c4001bc.htm");
            System.out.println(str2);
        }
        catch (Exception ex){
            System.out.println(String.format("Failed to test: %s", ex.toString()));
        }

    }
}

// CSV Format Standard
// http://stackoverflow.com/questions/769621/dealing-with-commas-in-a-csv-file

