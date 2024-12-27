package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.text.StringSubstitutor;
import org.jsoup.Jsoup;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws IOException {
        var cmdLine = new CmdLine();
        var parser = new CmdLineParser(cmdLine);
        try {
            parser.parseArgument(args);
        } catch(CmdLineException e) {
            e.printStackTrace(System.err);
            System.err.println("Usage:");
            parser.printUsage(System.err);
        }

        var inputFile = cmdLine.inputFile;
        var outputFile = cmdLine.outputFile;
        var slides = resolveSlides(cmdLine.slidesFile);
        var fontsSubstitutions = resolveFontsSubstitutions(Optional.ofNullable(cmdLine.fontsSubstitutionsFile));

        validateSlides(slides);

        var svgDocument = Jsoup.parse(new File(inputFile), "UTF-8", "");

        var templateContent = loadFileContentFromClasspathAsString("org/example/scriptTemplate.xml");
        var valuesMap = Map.of("slides", slides);
        var stringSubstitutor = new StringSubstitutor(valuesMap);
        var scriptContent = stringSubstitutor.replace(templateContent);
        var scriptFragment = Jsoup.parseBodyFragment(scriptContent, "").selectFirst("script");

        var fontsContent = loadFileContentFromClasspathAsString("org/example/fonts.xml");

        var svgElement = svgDocument.selectFirst("svg");
        svgElement.prependChild(scriptFragment);

        var styleElement = svgElement.selectFirst("defs style");
        styleElement.text(fontsContent);

        var elementsWithFontFamilyAttribute = svgElement.select("[font-family]");
        for(var elementWithFontFamilyAttribute : elementsWithFontFamilyAttribute) {
            var fontFamilyAttribute = elementWithFontFamilyAttribute.attr("font-family");
            for(var entry : fontsSubstitutions.entrySet()) {
                var fromFont = entry.getKey();
                var toFont = entry.getValue();
                fontFamilyAttribute = fontFamilyAttribute.replaceAll(fromFont, toFont);
            }
            elementWithFontFamilyAttribute.attr("font-family", fontFamilyAttribute);
        }

        try(var fileWriter = new BufferedWriter(new FileWriter(new File(outputFile)))) {
            fileWriter.write(svgDocument.selectFirst("svg").outerHtml());
        }
    }

    private static String loadFileContentFromClasspathAsString(String filePath) throws IOException {
        try(var bufferedReader = new BufferedReader(new InputStreamReader(Main.class.getClassLoader().getResourceAsStream(filePath), "UTF-8"))) {
            return bufferedReader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static void validateSlides(String slides) {
        var gson = new Gson();
        var slidesJsonElement = gson.fromJson(slides, JsonElement.class);
        var slidesJsonObject = slidesJsonElement.getAsJsonObject();
        var boxesObject = slidesJsonObject.get("boxes").getAsJsonObject();
        var numberOfBoxes = boxesObject.entrySet().size();
        if(numberOfBoxes < 1) {
            throw new IllegalArgumentException("Need at least one box");
        }
        for(var boxEntry : boxesObject.entrySet()) {
            var boxObject = boxEntry.getValue().getAsJsonObject();
            validateBoxObject(boxObject);
        }
        var boxIds = boxesObject.entrySet().stream().map(entry -> entry.getKey()).collect(Collectors.toSet());
        var initialBoxId = slidesJsonObject.get("initialBox").getAsString();
        if(!boxIds.contains(initialBoxId)) {
            throw new IllegalArgumentException("initialBoxId=" + initialBoxId + " is not contained in boxIds=" + boxIds);
        }
        var transitionsArray = slidesJsonObject.get("transitions").getAsJsonArray();
        for(var transitionElement : transitionsArray) {
            var transitionObject = transitionElement.getAsJsonObject();
            validateTransitionObject(transitionObject, boxIds);
        }
    }

    private static void validateBoxObject(JsonObject boxObject) {
        var topLeftObject = boxObject.get("topLeft").getAsJsonObject();
        validatePointObject(topLeftObject);
        var bottomRightObject = boxObject.get("bottomRight").getAsJsonObject();
        validatePointObject(bottomRightObject);
    }

    private static void validatePointObject(JsonObject pointObject) {
        pointObject.get("x").getAsNumber();
        pointObject.get("y").getAsNumber();
    }

    private static void validateTransitionObject(JsonObject transitionObject, Set<String> boxIds) {
        var nextBoxString = transitionObject.get("nextBox").getAsString();
        if(!boxIds.contains(nextBoxString)) {
            throw new IllegalArgumentException("nextBox=" + nextBoxString + " does not exist in boxIds=" + boxIds);
        }
        var animationDurationMillis = transitionObject.get("animationDurationMillis").getAsNumber().longValue();
        if(animationDurationMillis < 0) {
            throw new IllegalArgumentException("animationDurationMillis cannot be negative");
        }
    }

    private static String resolveSlides(String slidesFile) {
        try(var reader = new BufferedReader(new FileReader(slidesFile))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> resolveFontsSubstitutions(Optional<String> maybeFontsSubstitutionsFile) {
        return maybeFontsSubstitutionsFile.map(fontsSubstitutionsFile -> {
            Map<String, String> fontsSubstitutions = new HashMap<>();
            var gson = new Gson();
            try(var reader = new BufferedReader(new FileReader(fontsSubstitutionsFile))) {
                var substitutionsArray = gson.fromJson(reader, JsonElement.class).getAsJsonArray();
                for(var substitutionElement : substitutionsArray) {
                    var substitutionObject = substitutionElement.getAsJsonObject();
                    var fromFont = substitutionObject.get("fromFont").getAsString();
                    var toFont = substitutionObject.get("toFont").getAsString();
                    fontsSubstitutions.put(fromFont, toFont);
                }
                return fontsSubstitutions;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).orElse(Map.of());
    }

    private static final class CmdLine {

        @Option(
            name = "-inputFile",
            required = true,
            usage = "-inputFile /path/to/someFile.svg the input svg file"
        )
        private String inputFile = null;

        @Option(
            name = "-outputFile",
            required = true,
            usage = "-outputFile /path/to/someOutputFile.svg the output svg file"
        )
        private String outputFile = null;

        @Option(
            name = "-slidesFile",
            required = true,
            usage = "-slidesFile /path/to/some/slidesFile.json the input slides file"
        )
        private String slidesFile = null;

        @Option(
            name = "-fontsSubstitutionsFile",
            required = false,
            usage = "-fontsSubstitutionsFile /path/to/some/fontsSubstitutions.json the font substitutions file"
        )
        private String fontsSubstitutionsFile = null;
    }
} 
