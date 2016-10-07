package com.pin2pin;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by iRobot on 04/Oct/2016.
 */
public class mouser_Extractor {

    public static String BASE_URL = "http://eu.mouser.com";
    public static String LIST_ALL_URL = "/Electronic-Components/?";
    public static String PROBLEM_FILE = "Problem.txt";
    public static String PATH_RECOVERY = "Recovery.txt";
    public static String OUTPUT_FOLDER = "data";

    public static String LATEST_SUBCAT = "";
    public static int LATEST_PAGE = 0;
    public static String LATEST_TIMESTAMP = "";

    public static String LAST_STAGE = "";
    public static int LAST_PAGE = 0;
    public static String LAST_TIMESTAMP = "";

    public static int totalAccumulator = 0;
    public static int totalItems = 0;
    public static int currAccumulator = 0;

    public static int ITEM_PER_PAGE = 25;

    public static int runFrom = 0;
    public static int runTo = Integer.MAX_VALUE;

    public static void main(String[] args) {

        System.out.println("*** START ***");

        try{
            test();
            if (args.length > 0){
                runFrom = Integer.parseInt(args[0]);

                System.out.println(String.format("Running from subcat: %d", runFrom));

                if (args.length > 1){
                    runTo = Integer.parseInt(args[1]);

                    System.out.println(String.format("Running to subcat: %d", runTo));
                }

                if (runFrom > runTo)
                    throw new MyBusinessException("runFrom > runTo");
                if (runFrom < 0 || runTo < 0)
                    throw new MyBusinessException("runFrom/runTo must be >= 0");
            }

            // Restoring last stage if any.
            if (Files.exists(Paths.get(PATH_RECOVERY))){
                restoreLastStage();
            }

            // Creating the data folder where all CSV are saved.
            File directory = new File(OUTPUT_FOLDER);
            if (! directory.exists()){
                directory.mkdir();
            }

            ArrayList<SubCat> listOfFinalSubCats = null;

            // Quickly restore the all subcats.
            if (Files.exists(Paths.get("AllSubCat.ser"))){
                listOfFinalSubCats = (ArrayList<SubCat>)Helper.loadObjectFromFile("AllSubCat.ser");
                totalItems = (int)Helper.loadObjectFromFile("TotalItems.ser");
            }
            else{
                // ...or we have to re-fetch all the subcats again.
                listOfFinalSubCats = new ArrayList<>();

                Document docHomepage = Helper.downloadHtmlDocument(BASE_URL + LIST_ALL_URL);

                String abc = docHomepage.select("tr[id=ctl00_ContentMain_tr2] > td").get(0).ownText().replace(".", "").replace(",", "").trim();
                abc = Helper.removeAnnoyingCharacters(abc).trim();
                totalItems = Integer.parseInt(abc);

                Elements listSubCat = docHomepage.select("a[class=SearchResultsSubLevelCategory]");

                System.out.println(String.format("On the main page, there are [%d] links needed to follow and [%d] items to fetch.", listSubCat.size(), totalItems));

                for(int i = 0; i < listSubCat.size(); i++){

                    String urlSubCat = Helper.makeAbsoluteUrl(BASE_URL + LIST_ALL_URL, listSubCat.get(i).attr("href"));

                    System.out.println(String.format("Peeking page %d/%d... [%s]", i+1, listSubCat.size(), urlSubCat));

                    Document docSubCatPage = Helper.downloadHtmlDocument(urlSubCat);

                    // Check if there are still subcats even more...
                    Elements grandSubCatTable = docSubCatPage.select("div[id=CategoryControlTop]");
                    if (grandSubCatTable.size() > 0){
                        // Yes, there ARE some grand subcats.
                        Elements listOfGrandSubCats = grandSubCatTable.get(0).select("a[class=SearchResultsSubLevelCategory]");
                        for(int j = 0; j < listOfGrandSubCats.size(); j++){
                            String urlGrandSubCat = Helper.makeAbsoluteUrl(urlSubCat, listOfGrandSubCats.get(j).attr("href"));
                            listOfFinalSubCats.add(new SubCat(listOfGrandSubCats.get(j).text(), urlGrandSubCat));
                        }
                    }
                    else{
                        // There is NO more grand subcat. Itself is the the final subcat.
                        listOfFinalSubCats.add(new SubCat(docSubCatPage.select("h1").get(0).text(), urlSubCat));
                    }
                }

                Helper.saveObjectToFile(listOfFinalSubCats, "AllSubCat.ser");
                Helper.saveObjectToFile(totalItems, "TotalItems.ser");
            }

            // All we need to do now is to traverse through "listOfFinalSubCats" and get the items within them...
            System.out.println(String.format("All links need fetching...: %d", listOfFinalSubCats.size()));

            ////////////////////////////////////////////////////////////////////////////////////////

            LATEST_SUBCAT = "";
            long starttime = System.currentTimeMillis();

            for(int subCatIndex = 0; subCatIndex < listOfFinalSubCats.size(); subCatIndex++){

                // Try to reach the specificed range of SubCat.
                if (subCatIndex >= runFrom && subCatIndex <= runTo) {

                    SubCat currSc = listOfFinalSubCats.get(subCatIndex);

                    // for debug
                    //currSc = new SubCat("RAM Miscellaneous", "http://eu.mouser.com/Semiconductors/Integrated-Circuits-ICs/Memory/RAM-Miscellaneous/_/N-98xmd/");

                    System.out.println(String.format("Crawling Subcat %d/%d [%s]: [%s]", subCatIndex+1, listOfFinalSubCats.size(), currSc.getName(), currSc.getLink()));

                    if (LAST_STAGE != null && LAST_STAGE.length() > 0){
                        if (currSc.getLink().equals(LAST_STAGE)){
                            // Found last stage
                            LAST_STAGE = null;
                        }
                        else{
                            System.out.println(String.format("Move next because already done: [%s] => [%s]", currSc.getName(), currSc.getLink()));
                            continue;
                        }
                    }
                    else{
                        // We're not in the Recovery Mode. Or we have exited from it.
                    }

                    LATEST_SUBCAT = currSc.getLink();

                    // Sort by "Mouser Part No Ascending".
                    String urlSubCat = String.format("%s?Ns=MouserPartNumber", currSc.getLink());
                    Document docSubCat = Helper.downloadHtmlDocument(urlSubCat);

                    // Get the breadcrumbs.
                    String strBreadcrumbs = "";
                    Elements listOfBreadCrumbs = docSubCat.select("a[id$=lnkBreadcrumb]");
                    if (listOfBreadCrumbs.size() > 0){
                        for(int breadcrumb = 0; breadcrumb < listOfBreadCrumbs.size(); breadcrumb++){
                            strBreadcrumbs += listOfBreadCrumbs.get(breadcrumb).text().trim() + ">";
                        }
                        strBreadcrumbs = strBreadcrumbs.substring(0, strBreadcrumbs.length()-1);
                    }
                    else{
                        throw new MyBusinessException(String.format("[%s] not have a breadcrumb", currSc.getLink()));
                    }

                    // Get the total numberOfItems of products.
                    Elements productLink = docSubCat.select("a[id=ctl00_ContentMain_liProductsLink]");

                    // Special case! When a catefory contains one product only.
                    // http://eu.mouser.com/Semiconductors/Integrated-Circuits-ICs/Memory/RAM-Miscellaneous/_/N-98xmd/
                    if (productLink.size() == 0){
                        Helper.logProblem(String.format("%s not have Product Link", currSc.toString()));
                        continue;
                    }

                    Element lnkNumProduct = productLink.get(0);
                    Element spanNumber = lnkNumProduct.select("span[id=ctl00_ContentMain_lblProductCount").get(0);
                    String strNumber = spanNumber.text().replace(".", "").replace(",", "").replace("(", "").replace(")","").trim();
                    int numberOfItems = Integer.parseInt(strNumber);

                    // Each page, we can get maximum 25 items for each page.
                    int numberOfPages = (int)Math.ceil(numberOfItems / (double)ITEM_PER_PAGE);

                    // Get the CSV headers.
                    Element headersEle = docSubCat.select("tr[class=SearchResultColumnHeading]").get(0);
                    Elements listOfHeaders = headersEle.select("th");
                    String strHeaders = "";
                    for(int header = 0; header < listOfHeaders.size(); header++){

                        String headerName = listOfHeaders.get(header).text().trim();
                        if (headerName.length() == 0){

                            if (listOfHeaders.get(header).select("img").size() > 0){
                                headerName = "pdf";
                            }
                            else{
                                throw new MyBusinessException(String.format("Blank header: %d", header));
                            }
                        }

                        // *** Validation ***
                        if (!Helper.checkStringGoodForCSVFormat(headerName)){
                            throw new MyBusinessException("Invalid header for CSV: " + headerName);
                        }
                        // END Validation

                        // Force the data to be text.
                        headerName = Helper.makeStringGoodForCSVFormat(headerName);

                        strHeaders += headerName + ",";
                    }

                    // Remove the last comma, and add new line character
                    strHeaders = strHeaders.substring(0, strHeaders.length()-1);

                    // Calculate the numberOfItems of columns.
                    int numberOfColumns = strHeaders.split(",").length;

                    // Fix the current time stamp.
                    String currTimeStamp = "";
                    if (LAST_TIMESTAMP != null && LAST_TIMESTAMP.length() > 0){
                        currTimeStamp = LAST_TIMESTAMP;
                        LAST_TIMESTAMP = "";
                    }
                    else{
                        currTimeStamp = Helper.getTimeStamp();
                    }

                    LATEST_TIMESTAMP = currTimeStamp;

                    ////////////////////////////////////////////////////////////////////////////////////
                    // Now get all items from all pages within THIS category.
                    LATEST_PAGE = 0;

                    for (int page = 1; page <= numberOfPages; page++){

                        StringBuilder strDataPerPage = new StringBuilder();

                        System.out.println(String.format("Fetching [%s], page %d/%d : [%s]", currSc.getName(), page, numberOfPages, currSc.getLink()));

                        if (LAST_PAGE > 0) {
                            if (LAST_PAGE == page){
                                // Found last stage
                                LAST_PAGE = 0;
                            }
                            else{
                                System.out.println(String.format("Move next because already done: [%s] => [%s], page%d", currSc.getName(), currSc.getLink(), page));
                                continue;
                            }
                        }
                        else{
                            // We're not in the Recovery Mode. Or we have exited from it.
                        }

                        LATEST_PAGE = page;

                        if (page == 1){
                            // We already retrieve the Document Object for this page (first page)
                        }
                        else{
                            String urlNew = String.format("%s?Ns=MouserPartNumber&No=%d", currSc.getLink(), (page - 1) * ITEM_PER_PAGE);
                            docSubCat = Helper.downloadHtmlDocument(urlNew);
                        }

                        // Process the current Document Object
                        Elements listOfRows = docSubCat.select("div[id=searchResultsTbl] tr[data-index]");

                        // *** Validation ***
                        // If last page, probably there aren't fully 25 items.
                        if (page < numberOfPages && listOfRows.size() != ITEM_PER_PAGE){
                            //Helper.logProblem(String.format("Number of rows in page [%s] different than 25", currSc.getLink()));
                            throw new MyBusinessException(String.format("Number of rows in page [%s] different than 25", currSc.getLink()));
                        }
                        // END Validation

                        for(int row = 0; row < listOfRows.size(); row++){

                            Elements listOfCols = listOfRows.get(row).select(":root > td");

                            // *** Validation ***
                            if (numberOfColumns != listOfCols.size()){
                                throw new MyBusinessException(String.format("Number of columns for row %d mistmatch: %d vs %d", row, numberOfColumns, listOfCols.size()));
                            }
                            // END Validation

                            String strDataPerRow = "";
                            for(int col = 0; col < listOfCols.size(); col++){

                                String strDataPerCol = listOfCols.get(col).text().trim();
                                if (strDataPerCol.length() == 0){
                                    if (listOfCols.get(col).select("img").size() > 0){
                                        strDataPerCol = Helper.makeAbsoluteUrl(currSc.getLink(), listOfCols.get(col).select("img").get(0).attr("src"));
                                    }
                                }

                                // Remove annoying characters.
                                strDataPerCol = Helper.removeAnnoyingCharacters(strDataPerCol);

                                // Force the data to be text.
                                strDataPerCol = Helper.makeStringGoodForCSVFormat(strDataPerCol);

                                strDataPerRow += strDataPerCol + ",";
                            }

                            // Remove the last comma.
                            strDataPerRow = strDataPerRow.substring(0, strDataPerRow.length()-1);

                            // Add the data for this row into CSV.
                            strDataPerPage.append(strDataPerRow + "\n");
                        } // end of all rows within a page...

                        // For each page and each subcat, write the data immediately.
                        writeDataToFile(String.format("%s/%s#%d#%s.csv",
                                OUTPUT_FOLDER,
                                strBreadcrumbs.replace(">", "__").replace("/", "_"), numberOfItems, currTimeStamp),
                                strHeaders,
                                strDataPerPage.toString());

                        totalAccumulator += listOfRows.size();
                        currAccumulator += listOfRows.size();

                        // Print some statistics
                        long endtime = System.currentTimeMillis();
                        long timeSpent = endtime - starttime;
                        double speed = ((double)currAccumulator * 3600000) / (double)timeSpent;
                        double estimated = (totalItems - totalAccumulator) / speed;
                        System.out.println(String.format("Finished processing: %d/%d. Speed: %f items/hour. Estimated: %f hours to finish.",
                                totalAccumulator,
                                totalItems,
                                speed,
                                estimated));
                    } // end of all pages within a subcat...

                    LATEST_PAGE = 0;
                }
                else{
                    // We try to reach the range of Subcats specified.
                    continue;
                }
            } // end of all subcats...

            LATEST_SUBCAT = "";

            System.out.println("*** SUCCEED ***");
        }
        catch (Exception ex){
            System.out.println("*** Error ***\n" + ex.toString() + "\n" + ex.getMessage());
        }
        finally {
            try{
                keepLastStage(LATEST_SUBCAT, LATEST_PAGE, LATEST_TIMESTAMP);
            }
            catch (Exception ex){
                System.out.println(String.format("Error when keeping last stage: %s", ex.toString()));
            }
        }

        System.out.println("*** END ***");
    }

    public static void test() throws Exception{
        Helper.countLines("c:\\Users\\iRobot\\Desktop\\Work\\data\\");
        int stop = 100;
    }

    public static void writeDataToFile(String fileName, String headers, String data) throws Exception{

        String[] lines = data.split("\n");

        if (!Files.exists(Paths.get(fileName))){
            // If new file, we write the headers.
            Files.write(Paths.get(fileName), Arrays.asList(new String[]{headers}), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        //    Files.write(Paths.get(fileName), Arrays.asList(lines), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        //else{
            //Files.write(Paths.get(fileName), Arrays.asList(Arrays.copyOfRange(lines, 1, lines.length)), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        //    Files.write(Paths.get(fileName), Arrays.asList(lines), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        //}

        Files.write(Paths.get(fileName), Arrays.asList(lines), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    public static void keepLastStage(String link, int page, String timeStamp) throws Exception{

        if (Files.exists(Paths.get(PATH_RECOVERY))){
            Files.delete(Paths.get(PATH_RECOVERY));
        }

        if (link != null && link.length() > 0){
            Files.write(Paths.get(PATH_RECOVERY), Arrays.asList(new String[]{link,
                                                                            Integer.toString(page),
                                                                            Integer.toString(totalAccumulator),
                                                                            timeStamp}), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }

        System.out.println("Keeping last stage...");
    }

    public static void restoreLastStage() throws Exception{

        List<String> ls = Files.readAllLines(Paths.get(PATH_RECOVERY));

        LAST_STAGE = ls.get(0);
        LAST_PAGE = Integer.parseInt(ls.get(1));
        totalAccumulator = Integer.parseInt(ls.get(2));
        LAST_TIMESTAMP = ls.get(3);

        System.out.println("Restoring last stage...");
    }

    //////////////////////////// Utility Classes ///////////////////////////////////////////////////
    private static class SubCat implements Serializable{

        private String m_name;
        private String m_link;

        public SubCat(String name, String link){
            m_name = name;
            m_link = link;
        }

        public String getName(){
            return m_name;
        }

        public String getLink(){
            return m_link;
        }

        @Override
        public String toString(){
            return String.format("Sub cat [%s]: [%s]", m_name, m_link);
        }
    }
}

// No=50 means that fetching the items from 50th to 74th (each time 25 items, no way to change that).
// http://eu.mouser.com/Semiconductors/Discrete-Semiconductors/Diodes-Rectifiers/Bridge-Rectifiers/_/N-ax1mf/?No=50

