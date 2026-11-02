package com.stog.backend;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SemasPlaceImporterTest {
    @Test void quotedCommaEmbeddedNewlineAndDoubledQuoteAreParsedStreaming() throws Exception {
        SemasPlaceImporter.CsvReader csv=new SemasPlaceImporter.CsvReader(new StringReader("a,\"b,c\",\"line1\r\nline2\",\"say \"\"hi\"\"\"\r\n"));
        assertEquals(List.of("a","b,c","line1\nline2","say \"hi\""),csv.next());
        assertEquals(null,csv.next());
    }

    @Test void malformedAndBoundedRecordsAreRejected() {
        assertThrows(IOException.class,()->new SemasPlaceImporter.CsvReader(new StringReader("a,b\"c\n")).next());
        assertThrows(IOException.class,()->new SemasPlaceImporter.CsvReader(new StringReader("\"unterminated")).next());
        assertThrows(IOException.class,()->new SemasPlaceImporter.CsvReader(new StringReader("x".repeat(SemasPlaceImporter.FIELD_LIMIT+1)+"\n")).next());
    }
}
