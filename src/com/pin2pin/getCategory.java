package com.pin2pin;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Created by mint on 9/26/16.
 */
public class getCategory {

    public static int TIME_POLITENESS = 2000;
    public static int TIMEOUT_WEBSITE = 60000 * 2;

    public static String DIGIKEY_SEARCH = "http://www.digikey.com/product-search/en?keywords="; // replace space with "%20"
    public static String MOUSER_SEARCH = "http://eu.mouser.com/Search/Refine.aspx?Keyword=";    // replace space with "+"
    public static String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36";
    public static String PATH_OUTPUT = "final(Updated).csv";
    public static String PATH_INPUT = "final.csv";
    public static String PATH_RECOVERY = "Recovery.txt";

    public static String STAGE_LAST = "";
    public static String STAGE_LATEST = "";

    public static void main(String[] args) {
        //test();
        System.out.println("*** START ***");

        FileWriter fileWriter = null;
        CSVPrinter csvFilePrinter = null;

        FileReader fileReader = null;
        CSVParser csvFileParser = null;

        HashMap<String, String[]> hashMap = new HashMap<>();

        try{

            if (!Files.exists(Paths.get(PATH_RECOVERY))){

                Files.deleteIfExists(Paths.get(PATH_OUTPUT));

                fileWriter = new FileWriter(PATH_OUTPUT, false);
                csvFilePrinter = new CSVPrinter(fileWriter, CSVFormat.DEFAULT.withRecordSeparator("\n"));

                csvFilePrinter.printRecord("digikeyCat", "mouserCat", "PartNo", "Manufacturer", "Replacement", "ReplacementMfr", "Type", "LinkToPart");
            } else {    // restore the stages

                restoreStages();

                fileWriter = new FileWriter(PATH_OUTPUT, true); // apppend mode
                csvFilePrinter = new CSVPrinter(fileWriter, CSVFormat.DEFAULT.withRecordSeparator("\n"));
            }

            fileReader = new FileReader(PATH_INPUT);
            csvFileParser = new CSVParser(fileReader, CSVFormat.DEFAULT.withHeader());

            List<CSVRecord> allRecords = csvFileParser.getRecords();    // read all, at once.

            STAGE_LATEST = "";

            for(int i = 0; i < allRecords.size(); i++) {

                CSVRecord element = allRecords.get(i);

                System.out.println(String.format("Checking Part %d/%d: [%s] [%s]", i+1, allRecords.size(), element.get(0), element.get(5)));

                // Check if we are in Recovery mode.
                if (STAGE_LAST != null && STAGE_LAST.length() > 0){
                    // yes
                    if (STAGE_LAST.equals(element.get(0))){
                        // Found the last stage needed to recover.
                        STAGE_LAST = "";
                    }
                    else{
                        System.out.println(String.format("Already done. Move next."));
                        continue;
                    }
                }
                else{
                    // We are NOT in the Recovery mode. Do as usual.
                    // OR we have exited the Recovery mode.
                }

                String partNum = element.get(0);

                STAGE_LATEST = partNum;

                String catDigikey = "";
                String catMouser = "";

                // Check hashmap first.
                if (hashMap.containsKey(partNum)){
                    System.out.println(String.format("Already in Hashmap. Good."));

                    catDigikey = hashMap.get(partNum)[0];
                    catMouser = hashMap.get(partNum)[1];
                }
                else{

                    Thread.sleep(TIME_POLITENESS);

                    // For Digikey
                    String urlDigikey = constructDigikeySearchString(partNum);
                    Document docDigikey = Jsoup.connect(urlDigikey).userAgent(USER_AGENT).timeout(TIMEOUT_WEBSITE).get();
                    Elements breadCrumbDigikey = docDigikey.select("h1.breadcrumbs");
                    catDigikey = "";
                    if (breadCrumbDigikey.size() > 0){

                        // *** Validation ***
                        if (docDigikey.select("table[id=noResultsTable]").size() == 0) {
                            catDigikey = breadCrumbDigikey.get(0).text();
                        }
                    }

                    // For Mouser
                    String urlMouser = constructMouserSearchString(partNum);
                    Document docMouser = Jsoup.connect(urlMouser).userAgent(USER_AGENT).timeout(TIMEOUT_WEBSITE).get();
                    Elements breadCrumbMouser = docMouser.select("a[id*=Breadcrumbs]");
                    catMouser = "";
                    if (breadCrumbMouser.size() > 0){

                        // *** Validation ***
                        Elements eles = docMouser.select("span.NRSearchMsg");
                        if (eles.size() > 0 && eles.get(0).text().contains("did not return any results.")) {
                            // Here is "NOT FOUND PAGE".
                        }
                        else{
                            for(Element ele: breadCrumbMouser){
                                catMouser += (catMouser.length() > 0 ? " > " : "") + ele.text();
                            }
                        }
                    }

                    // Put to the hashmap for later quicker access.
                    hashMap.put(partNum, new String[]{catDigikey, catMouser});
                }

                // Save to new file
                csvFilePrinter.printRecord(catDigikey, catMouser, element.get(0), element.get(1),
                        element.get(2), element.get(3), element.get(4), element.get(5));
            }

            STAGE_LATEST = "";

            System.out.println("*** SUCCEED ***");
        }
        catch (Exception ex){
            System.out.println("*** Error ***\n" + ex.toString() + "\n" + ex.getMessage());
        }
        finally {
            try{
                if (fileWriter != null){
                    fileWriter.flush();
                    fileWriter.close();
                    csvFilePrinter.close();
                }
                fileWriter = null;
                csvFilePrinter = null;

                if (fileReader != null){
                    fileReader.close();
                    csvFileParser.close();
                }
                fileReader = null;
                csvFileParser = null;

                keepLastStages();
            }
            catch (Exception ex){
                System.out.println(String.format("Fail in 'finally': %s", ex.toString()));
            }
        }

        System.out.println("*** END ***");
    }

    public static String constructDigikeySearchString(String strKeyword){
        return DIGIKEY_SEARCH + strKeyword.replace(" ", "%20");
    }

    public static String constructMouserSearchString(String strKeyword){
        return MOUSER_SEARCH + strKeyword.replace(" ", "+");
    }

    public static void test(){
        String str1 = "MT46V16M16";

        try{
            Document abc = Jsoup.connect("http://eu.mouser.com/Search/Refine.aspx?Keyword=MT46V16M16")
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_WEBSITE)
                    .get();

            System.out.println(abc.select("div.detected_result").get(0).text());
        }
        catch (Exception ex){
            System.out.println(ex.toString());
        }
    }

    public static void keepLastStages() throws Exception {
        Files.deleteIfExists(Paths.get(PATH_RECOVERY));

        if (STAGE_LATEST != null && STAGE_LATEST.length() > 0){

            // save the stages ==> OVERWRITE any existing stages.
            BufferedWriter writer2 = Files.newBufferedWriter(Paths.get(PATH_RECOVERY), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            writer2.write(String.format("%s", STAGE_LATEST));
            writer2.flush();
            writer2.close();
        }

        System.out.println("Saving last known stages.");
    }

    public static void restoreStages() throws Exception{

        List<String> lineArray = Files.readAllLines(Paths.get(PATH_RECOVERY), StandardCharsets.UTF_8);
        STAGE_LAST = lineArray.get(0);

        STAGE_LATEST = STAGE_LAST;

        System.out.println("Restoring last known stages.");
    }
}

// Use Apache Common CSV for the parsing of CSV
// https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVParser.html

// Check my user agent string ==> it seems mouser needs a valid User Agent, instead of strange one like "Java" in case of default JSOUP user agent.
// https://www.whatismybrowser.com/detect/what-is-my-user-agent

// Part Number that exists on both Digikey and Mouser.
// http://www.digikey.com/product-search/en?keywords=MT46V16M16
// http://eu.mouser.com/Search/Refine.aspx?Keyword=MT46V16M16

