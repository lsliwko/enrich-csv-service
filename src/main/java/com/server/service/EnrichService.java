package com.server.service;

import com.server.exception.CsvException;
import com.server.service.mapper.EnrichFieldMapper;
import com.server.service.model.CsvModel;
import com.server.service.response.StreamingResponse;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.supercsv.exception.SuperCsvCellProcessorException;
import org.supercsv.io.CsvMapReader;
import org.supercsv.io.CsvMapWriter;
import org.supercsv.io.ICsvMapReader;
import org.supercsv.io.ICsvMapWriter;
import org.supercsv.prefs.CsvPreference;

import java.io.*;
import java.util.*;

@Service
public class EnrichService {

    final static private Logger logger = LoggerFactory.getLogger(EnrichService.class);

    public StreamingResponse enrichCsvAsStreamingResponse(
            Reader csvReader,
            CsvModel csvModel,
            EnrichFieldMapper enrichFieldMapper
    ) throws IOException, CsvException {

        // --- READ AND VALIDATE HEADERS BEFORE RETURNING STREAMING RESPONSE ---

        //input map reader remains unclosed after function exit
        ICsvMapReader inputMapReader = new CsvMapReader(csvReader, CsvPreference.STANDARD_PREFERENCE);

        String inputFieldName = enrichFieldMapper.getInputFieldName();
        String outputFieldName = enrichFieldMapper.getOutputFieldName();

        // the header columns are used as the keys to the Map
        String[] inputHeaders = inputMapReader.getHeader(true);
        if (!ArrayUtils.contains(inputHeaders, inputFieldName))
            throw new CsvException(String.format("Input csv doesn't contain '%s'", inputFieldName));
        if (ArrayUtils.contains(inputHeaders, outputFieldName))
            throw new CsvException(String.format("Input csv already contains '%s'", outputFieldName));

        // prepare output headers
        String[] outputHeaders = Arrays.copyOf(inputHeaders, inputHeaders.length);
        outputHeaders[ArrayUtils.indexOf(outputHeaders, inputFieldName)] = outputFieldName;


        // --- RETURN STREAMING RESPONSE ---

        //NOTE: this is not a pure function as we need unclosed inputMapReader to continue streaming
        return (outputStream, characterEncoding) -> {   //return StreamingResponse
            try (
                    Writer csvWriter = new OutputStreamWriter(outputStream, characterEncoding);
                    ICsvMapWriter outputMapWriter = new CsvMapWriter(csvWriter, CsvPreference.STANDARD_PREFERENCE)
            ) {

                // write headers
                outputMapWriter.writeHeader(outputHeaders);

                Map<String, Object> inputValuesMap;
                while (true) {  //while loop is v-fast (vs. streams)

                    //catch any parse exceptions
                    try {
                        inputValuesMap = inputMapReader.read(inputHeaders, csvModel.getProcessors());
                    } catch (SuperCsvCellProcessorException exception) {
                        logger.error(String.format("Error reading csv line no %d [%s]: %s", inputMapReader.getLineNumber(), inputMapReader.getUntokenizedRow(), exception.getMessage()), exception);
                        //skip line
                        continue;
                    }

                    //nothing more to read?
                    if (inputValuesMap == null) break;

                    //enrich csv row
                    Object inputFileValue = inputValuesMap.get(inputFieldName);
                    Optional<String> outputFieldValueOption = enrichFieldMapper.findOutputValueById(Objects.toString(inputFileValue));

                    //default value if missing (orElseGet doesn't support exceptions thrown by default-value supplier)
                    Object outputFieldValue = outputFieldValueOption.isPresent() ? outputFieldValueOption.get() : enrichFieldMapper.defaultValue();

                    //work on writable copy
                    Map<String, Object> outputValuesMap = new HashMap<>(inputValuesMap);

                    //exchange field
                    outputValuesMap.remove(inputFieldName);
                    outputValuesMap.put(outputFieldName, outputFieldValue);

                    outputMapWriter.write(outputValuesMap, outputHeaders);
                }
            }
        };
    }

}
