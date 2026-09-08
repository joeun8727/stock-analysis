package com.stockanalysis.data;

import com.github.pjfanning.xlsx.StreamingReader;
import com.stockanalysis.backtest.Bar;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 15열 3분봉 엑셀 파일을 SAX 기반 스트리밍 리더로 파싱해, 14 MB / 16만 행 워크북을 저메모리로
 * 읽습니다. 열은 위치로 바인딩합니다(이동평균 헤더가 맨숫자를 재사용해서 이름을 믿을 수 없습니다):
 *
 * <pre>
 * 0 일자(date) 1 시간(time) 2 open 3 high 4 low 5 close
 * 6 MA5 7 MA10 8 MA20 9 MA60 10 거래량 11 volMA5 12 volMA20 13 volMA60 14 volMA120
 * </pre>
 */
public class ExcelParser {

    public List<Bar> parse(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read excel: " + path, e);
        }
    }

    public List<Bar> parse(InputStream in) {
        List<Bar> bars = new ArrayList<>();
        try (Workbook workbook = StreamingReader.builder()
                .rowCacheSize(256)
                .bufferSize(8192)
                .open(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean header = true;
            for (Row row : sheet) {
                if (header) {
                    header = false;
                    continue;
                }
                LocalDate date = dateAt(row, 0);
                LocalTime time = timeAt(row, 1);
                if (date == null) {
                    continue; // 비어 있거나 불완전한 행은 건너뜁니다
                }
                LocalDateTime ts = LocalDateTime.of(date, time == null ? LocalTime.MIDNIGHT : time);
                bars.add(new Bar(
                        ts,
                        num(row, 2), num(row, 3), num(row, 4), num(row, 5),
                        num(row, 6), num(row, 7), num(row, 8), num(row, 9),
                        num(row, 10),
                        num(row, 11), num(row, 12), num(row, 13), num(row, 14)
                ));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse excel stream", e);
        }
        return bars;
    }

    private static double num(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null) {
            return Double.NaN;
        }
        CellType type = c.getCellType();
        try {
            if (type == CellType.NUMERIC) {
                return c.getNumericCellValue();
            }
            if (type == CellType.STRING) {
                String s = c.getStringCellValue().trim().replace(",", "");
                return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
            }
        } catch (RuntimeException ignored) {
            // 아래로 흘려보냅니다
        }
        return Double.NaN;
    }

    private static LocalDate dateAt(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null || c.getCellType() != CellType.NUMERIC) {
            return null;
        }
        try {
            return c.getLocalDateTimeCellValue().toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalTime timeAt(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null || c.getCellType() != CellType.NUMERIC) {
            return null;
        }
        try {
            return c.getLocalDateTimeCellValue().toLocalTime();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
