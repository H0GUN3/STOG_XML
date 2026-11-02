package com.stog.backend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SemasPlaceImportCommand {
    private static final long EXPECTED_SIZE=352_699_739L;
    private static final String EXPECTED_SHA="57aa361544108ff4fc73334f87d2ced61e41fe8638dd70bcd842930f865d2386";
    private SemasPlaceImportCommand(){}
    public static void main(String[] args)throws Exception{
        Options options=Options.parse(args);Path zipPath=options.zip.toAbsolutePath().normalize();
        if(Files.size(zipPath)!=EXPECTED_SIZE||!EXPECTED_SHA.equals(sha256(zipPath)))throw new IllegalArgumentException("Official SEMAS archive identity mismatch");
        String url=requiredEnv("STOG_IMPORT_JDBC_URL"),user=requiredEnv("STOG_IMPORT_DB_USERNAME"),password=requiredEnv("STOG_IMPORT_DB_PASSWORD");
        if(!url.equals(requiredEnv("STOG_IMPORT_CONFIRM_TARGET"))||!(url.startsWith("jdbc:postgresql://127.0.0.1:")||url.startsWith("jdbc:postgresql://localhost:"))||!url.contains("currentSchema="))throw new IllegalArgumentException("Explicit isolated/local JDBC target confirmation is required");
        ArrayList<SemasPlaceImporter.ImportReport> reports=new ArrayList<>();SemasPlaceImporter importer=new SemasPlaceImporter();
        try(ZipFile zip=new ZipFile(zipPath.toFile(),Charset.forName("MS949"));Connection connection=DriverManager.getConnection(url,user,password)){
            List<? extends ZipEntry> entries=zip.stream().filter(entry->!entry.isDirectory()&&entry.getName().endsWith(".csv")&&(options.entry==null||options.entry.equals(entry.getName()))).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            if(entries.isEmpty()||(options.entry==null&&entries.size()!=16))throw new IllegalArgumentException("Expected SEMAS CSV entries are missing");
            for(ZipEntry entry:entries){try(BufferedReader reader=new BufferedReader(new InputStreamReader(zip.getInputStream(entry),StandardCharsets.UTF_8),64*1024)){reports.add(importer.importCsv(connection,reader,entry.getName(),options.maximumRows));}}
        }catch(SemasPlaceImporter.ImportFailure failure){System.err.println("{\"result\":\"FAILED\",\"reason\":\""+failure.reason()+"\",\"row\":"+failure.rowNumber()+"}");throw failure;}
        long rows=reports.stream().mapToLong(SemasPlaceImporter.ImportReport::rows).sum(),inserted=reports.stream().mapToLong(SemasPlaceImporter.ImportReport::inserted).sum(),updated=reports.stream().mapToLong(SemasPlaceImporter.ImportReport::updated).sum(),branches=reports.stream().mapToLong(SemasPlaceImporter.ImportReport::nonblankBranches).sum(),categoryCodes=reports.stream().mapToLong(SemasPlaceImporter.ImportReport::distinctCategoryCodes).sum(),review=reports.stream().mapToLong(SemasPlaceImporter.ImportReport::reviewDuplicateGroups).sum();
        System.out.println("{\"result\":\"PASS\",\"dataset_id\":\"15083033\",\"snapshot\":\"20260630\",\"files\":"+reports.size()+",\"rows\":"+rows+",\"inserted\":"+inserted+",\"updated\":"+updated+",\"nonblank_branches\":"+branches+",\"distinct_category_codes\":"+categoryCodes+",\"review_duplicate_groups\":"+review+"}");
    }
    private static String sha256(Path path)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[]buffer=new byte[1024*1024];for(int read;(read=input.read(buffer))>=0;)if(read>0)digest.update(buffer,0,read);}return HexFormat.of().formatHex(digest.digest());}
    private static String requiredEnv(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
    private record Options(Path zip,String entry,int maximumRows){static Options parse(String[]args){Path zip=null;String entry=null;int max=0;for(int i=0;i<args.length;i++){switch(args[i]){case"--zip"->zip=Path.of(value(args,++i));case"--entry"->entry=value(args,++i);case"--max-rows"->{try{max=Integer.parseInt(value(args,++i));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid max rows");}if(max<1||max>10_000)throw new IllegalArgumentException("max rows must be 1..10000");}default->throw new IllegalArgumentException("Unknown argument");}}if(zip==null)throw new IllegalArgumentException("--zip is required");return new Options(zip,entry,max);}private static String value(String[]args,int index){if(index>=args.length||args[index].isBlank())throw new IllegalArgumentException("Missing argument value");return args[index];}}
}
