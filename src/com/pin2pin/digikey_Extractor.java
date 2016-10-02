package com.pin2pin;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by mint on 9/29/16.
 */
public class digikey_Extractor {

    public static String BASE_URL = "http://www.digikey.com";
    public static String LIST_ALL_URL = "/product-search/en";
    public static int MAX_PAGESIZE = 500;   // for digikey, 500 is the maximum of items which can be displayed on a page.
    public static String OUTPUT_FOLDER = "data";
    public static String PROBLEM_FILE = "Problem.txt";

    public static String LAST_STAGE = null;
    public static String LAST_STAGE_FILENAME = null;
    public static String LATEST_STAGE = null;
    public static String LATEST_STAGE_FILENAME = null;
    public static String PATH_RECOVERY = "Recovery.txt";

    public static void main(String[] args) {

        System.out.println("*** START ***");

        try{
            //test();

            if (Files.exists(Paths.get(PATH_RECOVERY))){
                restoreLastStage();
            }

            long starttime = System.currentTimeMillis();

            File directory = new File(OUTPUT_FOLDER);
            if (! directory.exists()){
                directory.mkdir();
            }

            Document docHomepage = Helper.downloadHtmlDocument(BASE_URL + LIST_ALL_URL);

            int totalExpectedItems = Integer.parseInt(docHomepage.select("span[id=matching-records-count]").get(0).text().replace(",", ""));
            System.out.println(String.format("Expected total number of items: %d", totalExpectedItems));

            ArrayList<SubCat> listOfSubCat = getAll(docHomepage);

            int totalNumber = 0;

            LATEST_STAGE = null;

            for(SubCat sc: listOfSubCat){

                // for debug
                //sc = new SubCat("Bumpers, Feet, Pads, Grips Kits", 1, "http://www.digikey.com/product-search/en/kits/bumpers-feet-pads-grips-kits/2491655");

                if (LAST_STAGE != null){
                    if (sc.getLink().equals(LAST_STAGE)){
                        // Found last stage
                        LAST_STAGE = null;
                    }
                    else{
                        System.out.println(String.format("Move next because already done: %s => %s", sc.getName(), sc.getLink()));
                        continue;
                    }
                }
                else{
                    //
                }

                LATEST_STAGE = sc.getLink();

                SubCatResult scRes = null;

                try{
                    // Get the "FV" (e.g.: <input type="hidden" name="FV" value="fff40021,fff8048d" />)
                    scRes = graspFvInSubCat(sc);
                }
                catch (MyBusinessException mybe){
                    String logme = String.format("Link [%s] has problem: [%s]", sc.getLink(), mybe.toString());
                    System.out.println(logme);
                    logProblem(logme);
                    continue;   // just continue, no need to stop.
                }


                int maxpage = (int)Math.ceil(sc.getNumberOfItems() / (double)MAX_PAGESIZE);

                String currTimeStamp = Helper.getTimeStamp();
                String fileName = null;

                LATEST_STAGE_FILENAME = null;

                for(int page = 1; page <= maxpage; page++){

                    System.out.println(String.format("Checking [%s], page [%d]...", sc.getLink(), page));

                    // Get the data as they are (Download table).
                    graspItemsInSubCat(scRes, page, true);

                    if (fileName == null || fileName.length() == 0)
                        fileName = String.format("%s/%s#%d#%s.csv",
                                                    OUTPUT_FOLDER,
                                                    scRes.getBreadCrumbs().replace(">", "__").replace("/", "_"),
                                                    sc.getNumberOfItems(),
                                                    currTimeStamp);

                    LATEST_STAGE_FILENAME = fileName;

                    // Convert them into agreed format.
                    String dataFinal = standardizeCsvFormat(scRes.getCsvData(), currTimeStamp, "digikey", scRes.getBreadCrumbs());

                    // Save it onto disk.
                    writeDataToFile(fileName, dataFinal);
                }

                LATEST_STAGE_FILENAME = null;

                // *** Validation ***
                // Check the number of lines
                BufferedReader reader = new BufferedReader(new FileReader(fileName));
                int lines = 0;
                while (reader.readLine() != null) lines++;
                reader.close();

                if (lines - 1 != sc.getNumberOfItems()){
                    //throw new MyBusinessException("Expected items different than number of lines in CSV");
                    // Just continue!
                    String logme = String.format("Expected items [%d] different than number of lines in CSV [%d]", sc.getNumberOfItems(), lines - 1);
                    System.out.println(logme);
                    logProblem(logme);
                }
                // *** END Validation ***

                // Just some statistics
                totalNumber += sc.getNumberOfItems();
                long endtime = System.currentTimeMillis();
                double averageItemsPerHour = ((double)totalNumber * 3600000) / ((double)(endtime - starttime));

                System.out.println(String.format("Finished processing: %d/%d. Average: %f items/hour. Estimate Time Left: total %f hours to finish.",
                        totalNumber,
                        totalExpectedItems,
                        averageItemsPerHour,
                        (totalExpectedItems - totalNumber) / averageItemsPerHour));
            }

            LATEST_STAGE = null;

            System.out.println("*** SUCCEED ***");
        }
        catch (Exception ex){
            System.out.println("*** Error ***\n" + ex.toString() + "\n" + ex.getMessage());
        }
        finally {

            try{
                keepLastStage(LATEST_STAGE, LATEST_STAGE_FILENAME);
            }
            catch (Exception ex){
                System.out.println(String.format("Problem when keeping last stage: %s", ex.toString()));
            }
        }

        System.out.println("*** END ***");
    }

    public static SubCatResult graspFvInSubCat(SubCat sc) throws Exception{
        //String trickUrl = String.format("%s?page=%d&pageSize=%d", sc.getLink(), page, MAX_PAGESIZE);
        String trickUrl = String.format("%s?pageSize=%d", sc.getLink(), 25);    // 25 so that the page can be loaded quickly.

        // Download the page with the given page number.
        Document docSubCat = Helper.downloadHtmlDocument(trickUrl);

        // *** Validation ***
        // There is a breadcrumbs indicator.
        if (docSubCat.select("h1[class=breadcrumbs][itemprop=breadcrumb]").size() == 0)
            throw new MyBusinessException(String.format("[%s] not have a Breadcrumb", trickUrl));

        // The results indicate matches the expected.
        Elements eleResultCount = docSubCat.select("span[id=matching-records-count]");
        if (eleResultCount.size() == 0)
            throw new MyBusinessException(String.format("[%s] not have Result Count", trickUrl));

        if (!eleResultCount.get(0).text().replace(",", "").equals(String.valueOf(sc.getNumberOfItems())))
            throw new MyBusinessException(String.format("[%s] have Result Count not matched", trickUrl));
        // *** END Validation ***

        // Find the category breadcrumbs.
        String breadCrumbs = docSubCat.select("h1[class=breadcrumbs][itemprop=breadcrumb]").get(0).text();

        // Find the "download table" link.
        String secretVal = docSubCat.select("input[type=hidden][name=FV]").get(0).attr("value");

        return new SubCatResult(breadCrumbs, secretVal, sc.getLink());
    }

    public static void graspItemsInSubCat(SubCatResult scRes, int page, boolean getRoHS) throws Exception{

        // This value is filled in URL, so it follows URL Format.
        String secretVal = scRes.getSecretVal().replace(",", "%2C");

        // Construct the Download Table link.
        String trickDownloadUrl = String.format("http://www.digikey.com/product-search/download.csv?FV=%s&mnonly=0&newproducts=0&ColumnSort=0&page=%d&stock=0&pbfree=0&rohs=0&quantity=0&ptm=0&fid=0&pageSize=%d", secretVal, page, MAX_PAGESIZE);

        // Download the CSV from the Download Table link.
        String partialCsv = Helper.downloadTextFile(trickDownloadUrl);

        // Check RoHS (not available in CSV via Download Table) ==> check through web interface, which is costly.
        if (getRoHS){

            Document docSubCat = Helper.downloadHtmlDocument(String.format("%s?page=%d&pageSize=%d", scRes.getLink(), page, MAX_PAGESIZE));
            Elements listEle = docSubCat.select("td.tr-dkPartNumber.nowrap-culture");

            String[] lines = partialCsv.split("\n");

            // *** Validation ***
            if (listEle.size() != lines.length - 1){
                throw new MyBusinessException("Number of RoHS via GUI mistmatches CSV via Download Table");
            }
            // *** END Validation

            StringBuilder strBuilder = new StringBuilder();

            for(int i = 0; i < lines.length; i++){
                if (i == 0){
                    strBuilder.append(String.format("RoHS,%s", lines[i]));
                }
                else{
                    Elements imgs = listEle.get(i-1).select("img.rohs-foilage");
                    if (imgs.size() == 0){
                        strBuilder.append(String.format("unknown,%s", lines[i]));
                    }
                    else{
                        if (imgs.get(0).attr("src").contains("leaf-no.png")){
                            strBuilder.append(String.format("no,%s", lines[i]));
                        }
                        else{
                            strBuilder.append(String.format("yes,%s", lines[i]));
                        }
                    }
                }

                strBuilder.append("\n");
            }

            partialCsv = strBuilder.toString();
        }



        scRes.setCsvData(partialCsv);
        scRes.setPage(page);
    }


    // Given the base url, get all the links that need to be scraped.
    // E.g.: Personal Protective Equipment (PPE) (242 items)
    public static ArrayList<SubCat> getAll(Document docHomepage) throws Exception {

        ArrayList<SubCat> listOfSubCat = new ArrayList<>();

        Elements listOfCategoryLinks = docHomepage.select("div[id=productIndexList] > ul[class=catfiltersub] > li");
        Pattern r = Pattern.compile("\\((\\d+) items\\)");

        for(Element ele: listOfCategoryLinks){

            String name = null;
            String link = null;

            if (ele.select("a[href]").size() > 0){
                Element eleLink = ele.select("a[href]").get(0);

                name = eleLink.ownText();
                link = BASE_URL + eleLink.attr("href");
            }
            else
                throw new MyBusinessException("Not found the link in sub category: " + ele.text());

            int numberOfItems = -1;

            Matcher m = r.matcher(ele.ownText());
            if (m.find()){
                numberOfItems = Integer.parseInt(m.group(1)); // group(0) is the entire match.
            }
            else
                throw new MyBusinessException("Not found the number of items in sub category: " + ele.text());


            listOfSubCat.add(new SubCat(name, numberOfItems, link));
        }

        return listOfSubCat;
    }

    /*
    Add these columns: datetime, source, category
     */
    public static String standardizeCsvFormat(String input, String datetime, String source, String category){

        StringBuilder strBuilder = new StringBuilder();

        String[] lines = input.split("\n");

        for(int i = 0; i < lines.length; i++){
            if (i == 0){
                strBuilder.append(String.format("datetime,source,category,%s\n", lines[i]));
            }
            else{
                strBuilder.append(String.format("%s,%s,\"%s\",%s\n", datetime, source, category, lines[i]));
            }
        }

        return strBuilder.toString();
    }

    public static void test(){

        try{


            int stop = 100;
        }
        catch (Exception ex){
            int stop = 100;
        }
    }

    //////////////////////////// Utility Classes ///////////////////////////////////////////////////
    private static class SubCat {

        private String m_name;
        private int m_numOfItems;
        private String m_link;

        public SubCat(String name, int numOfItems, String link){
            m_name = name;
            m_numOfItems = numOfItems;
            m_link = link;
        }

        public String getName(){
            return m_name;
        }

        public int getNumberOfItems(){
            return m_numOfItems;
        }

        public String getLink(){
            return m_link;
        }

        @Override
        public String toString(){
            return String.format("%s (%s items): %s", m_name, m_numOfItems, m_link);
        }
    }

    private static class SubCatResult {

        private String m_breadCrumbs;   // not change regardless of number of page chosen.
        private String m_secretVal;     // not change regardless of number of page chosen.
        private int m_page;
        private String m_csvData;
        private String m_link;

        public SubCatResult(String breadCrumbs, String secretVal, String link){
            m_breadCrumbs = breadCrumbs;
            m_secretVal = secretVal;
            m_link = link;
        }

        public String getCsvData(){
            return m_csvData;
        }

        public int getPage(){
            return m_page;
        }

        public String getBreadCrumbs(){
            return m_breadCrumbs;
        }

        public String getSecretVal(){
            return m_secretVal;
        }

        public String getLink(){
            return m_link;
        }

        public void setPage(int page){
            m_page = page;
        }

        public void setCsvData(String csvData){
            m_csvData = csvData;
        }
    }

    //////////////////////////// Utility Functions /////////////////////////////////////////////////
    public static void logProblem(String str) throws Exception{
        BufferedWriter writer = Files.newBufferedWriter(Paths.get(PROBLEM_FILE), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writer.write(String.format("%s;%s\n", Helper.getTimeStamp(), str));
        writer.flush();
        writer.close();
    }

    /*
    As discussed, we don't need to parse the CSV, just copy them as they are.
     */
    public static void writeDataToFile(String fileName, String data) throws Exception{

        String[] lines = data.split("\n");

        if (!Files.exists(Paths.get(fileName))){
            //Files.write(Paths.get(fileName), Arrays.asList(lines), Charset.defaultCharset(), StandardOpenOption.CREATE);
            Files.write(Paths.get(fileName), Arrays.asList(lines), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
        else{
            //Files.write(Paths.get(fileName), Arrays.asList(Arrays.copyOfRange(lines, 1, lines.length)), Charset.defaultCharset(), StandardOpenOption.APPEND);
            Files.write(Paths.get(fileName), Arrays.asList(Arrays.copyOfRange(lines, 1, lines.length)), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    public static void keepLastStage(String link, String fileNameToDelete) throws Exception{

        if (fileNameToDelete != null && fileNameToDelete.length() > 0){
            if (Files.exists(Paths.get(fileNameToDelete))){
                Files.delete(Paths.get(fileNameToDelete));
            }
        }

        if (Files.exists(Paths.get(PATH_RECOVERY))){
            Files.delete(Paths.get(PATH_RECOVERY));
        }

        if (link != null && link.length() > 0){
            Files.write(Paths.get(PATH_RECOVERY), Arrays.asList(new String[]{link}), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }

        System.out.println("Keeping last stage...");
    }

    public static void restoreLastStage() throws Exception{

        LAST_STAGE = Files.readAllLines(Paths.get(PATH_RECOVERY)).get(0);

        System.out.println("Restoring last stage...");
    }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Test Link
// Download link with < 500 items: http://www.digikey.com/product-search/download.csv?FV=fff4000b%2Cfff80054&mnonly=0&newproducts=0&ColumnSort=0&page=1&stock=0&pbfree=0&rohs=0&quantity=0&ptm=0&fid=0&pageSize=500
// Download link with > 500 items: http://www.digikey.com/product-search/download.csv?FV=fff4000b%2Cfff80047&mnonly=0&newproducts=0&ColumnSort=0&page=1&stock=0&pbfree=0&rohs=0&quantity=0&ptm=0&fid=0&pageSize=500

// Page 2 of this "http://www.digikey.com/product-search/en/cable-assemblies/circular-cable-assemblies/1573006", contains malformed CSV.
// -,-,MIKQ6-7SH061-ND,MIKQ6-7SH061,"ITT Cannon, LLC",""MICRO Q F STR 18"""" MULTI"",2,2,"257.86000","0","1","*","Active"

// Only one item... ==> breadcrumb in div, instead of h1
// Bumpers, Feet, Pads, Grips Kits ==> http://www.digikey.com/product-search/en/kits/bumpers-feet-pads-grips-kits/2491655
