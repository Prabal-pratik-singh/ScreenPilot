package com.screenpilot.signage.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Excel (Apache POI) and branded PDF (openhtmltopdf) exports for both reports. */
@Service
public class ExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
    private static final byte[] MARIGOLD = {(byte) 0xF6, (byte) 0xA8, 0x21};
    private static final byte[] INK = {0x16, 0x23, 0x3F};

    // ------------------------------------------------------------ excel

    public byte[] proofOfPlayXlsx(ReportService.ProofOfPlayReport report, LocalDate from, LocalDate to) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Proof of Play");
            writeBrandHeader(wb, sheet, "Proof-of-Play Report", from, to,
                    report.totalPlays() + " total plays · " + Math.round(report.totalSeconds() / 60.0) + " minutes on screen");
            String[] cols = {"Creative", "Type", "Screen", "Play count", "Total seconds", "First played (IST)", "Last played (IST)"};
            writeTableHeader(wb, sheet, 3, cols);
            int r = 4;
            for (ReportService.ProofOfPlayRow row : report.rows()) {
                Row xr = sheet.createRow(r++);
                xr.createCell(0).setCellValue(row.creative());
                xr.createCell(1).setCellValue(row.itemType() == null ? "" : row.itemType());
                xr.createCell(2).setCellValue(row.screenName());
                xr.createCell(3).setCellValue(row.playCount());
                xr.createCell(4).setCellValue(row.totalSeconds());
                xr.createCell(5).setCellValue(istString(row.firstPlayed()));
                xr.createCell(6).setCellValue(istString(row.lastPlayed()));
            }
            autosize(sheet, cols.length);
            return toBytes(wb);
        }
    }

    public byte[] uptimeXlsx(ReportService.UptimeReport report, LocalDate from, LocalDate to) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Screen Uptime");
            writeBrandHeader(wb, sheet, "Screen Uptime Report", from, to,
                    report.rows().size() + " screens · " + report.redFlags().size() + " under 90%");
            List<ReportService.UptimeDay> days = report.rows().isEmpty() ? List.of() : report.rows().get(0).days();
            String[] cols = new String[3 + days.size() + 1];
            cols[0] = "Screen";
            cols[1] = "Store";
            cols[2] = "City";
            for (int i = 0; i < days.size(); i++) {
                cols[3 + i] = days.get(i).date();
            }
            cols[cols.length - 1] = "Average %";
            writeTableHeader(wb, sheet, 3, cols);
            int r = 4;
            for (ReportService.UptimeRow row : report.rows()) {
                Row xr = sheet.createRow(r++);
                xr.createCell(0).setCellValue(row.screenName());
                xr.createCell(1).setCellValue(row.storeName() == null ? "" : row.storeName());
                xr.createCell(2).setCellValue(row.city() == null ? "" : row.city());
                for (int i = 0; i < row.days().size(); i++) {
                    xr.createCell(3 + i).setCellValue(row.days().get(i).pct());
                }
                xr.createCell(cols.length - 1).setCellValue(row.avgPct());
            }
            autosize(sheet, Math.min(cols.length, 12));
            return toBytes(wb);
        }
    }

    private void writeBrandHeader(XSSFWorkbook wb, Sheet sheet, String title, LocalDate from, LocalDate to, String subtitle) {
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle titleStyle = wb.createCellStyle();
        ((org.apache.poi.xssf.usermodel.XSSFCellStyle) titleStyle).setFillForegroundColor(new XSSFColor(INK, null));
        titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        titleStyle.setFont(titleFont);

        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(28);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("screenPilot  ·  " + title);
        c0.setCellStyle(titleStyle);
        for (int i = 1; i < 8; i++) {
            r0.createCell(i).setCellStyle(titleStyle);
        }

        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("Period: " + from + " to " + to + " (IST)   ·   " + subtitle
                + "   ·   Generated " + ZonedDateTime.now(TimeUtil.IST).format(TS) + " IST");
    }

    private void writeTableHeader(XSSFWorkbook wb, Sheet sheet, int rowIdx, String[] cols) {
        Font headFont = wb.createFont();
        headFont.setBold(true);
        headFont.setColor(IndexedColors.BLACK.getIndex());
        CellStyle headStyle = wb.createCellStyle();
        ((org.apache.poi.xssf.usermodel.XSSFCellStyle) headStyle).setFillForegroundColor(new XSSFColor(MARIGOLD, null));
        headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headStyle.setFont(headFont);
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < cols.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(cols[i]);
            cell.setCellStyle(headStyle);
        }
    }

    private void autosize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 512, 12000));
        }
    }

    private byte[] toBytes(Workbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private String istString(java.time.Instant instant) {
        return instant == null ? "" : ZonedDateTime.ofInstant(instant, TimeUtil.IST).format(TS);
    }

    // ------------------------------------------------------------ pdf

    public byte[] proofOfPlayPdf(ReportService.ProofOfPlayReport report, LocalDate from, LocalDate to) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (ReportService.ProofOfPlayRow r : report.rows()) {
            rows.append("<tr>")
                    .append(td(escape(r.creative())))
                    .append(td(r.itemType() == null ? "" : r.itemType()))
                    .append(td(escape(r.screenName())))
                    .append(tdRight(String.valueOf(r.playCount())))
                    .append(tdRight(String.format("%.0f s", r.totalSeconds())))
                    .append(td(istString(r.firstPlayed())))
                    .append(td(istString(r.lastPlayed())))
                    .append("</tr>");
        }
        String html = pdfShell("Proof-of-Play Report", from, to,
                "<div class='stats'><div><b>" + report.totalPlays() + "</b> total plays</div>"
                        + "<div><b>" + Math.round(report.totalSeconds() / 60.0) + "</b> minutes on screen</div>"
                        + "<div><b>" + report.rows().size() + "</b> creative × screen combinations</div></div>"
                        + "<table><thead><tr><th>Creative</th><th>Type</th><th>Screen</th>"
                        + "<th class='r'>Plays</th><th class='r'>On screen</th><th>First played (IST)</th><th>Last played (IST)</th></tr></thead>"
                        + "<tbody>" + rows + "</tbody></table>");
        return renderPdf(html);
    }

    public byte[] uptimePdf(ReportService.UptimeReport report, LocalDate from, LocalDate to) throws Exception {
        StringBuilder redFlags = new StringBuilder();
        if (!report.redFlags().isEmpty()) {
            redFlags.append("<h2>Red flags — worst performers (&lt; 90% average)</h2><table><thead><tr><th>Screen</th><th>Store</th><th class='r'>Average online %</th></tr></thead><tbody>");
            for (ReportService.UptimeRow r : report.redFlags()) {
                redFlags.append("<tr>").append(td(escape(r.screenName()))).append(td(escape(nullSafe(r.storeName()))))
                        .append("<td class='r bad'>").append(r.avgPct()).append("%</td></tr>");
            }
            redFlags.append("</tbody></table>");
        }
        StringBuilder rows = new StringBuilder();
        for (ReportService.UptimeRow r : report.rows()) {
            rows.append("<tr>").append(td(escape(r.screenName()))).append(td(escape(nullSafe(r.storeName()))))
                    .append(td(escape(nullSafe(r.city()))));
            for (ReportService.UptimeDay d : r.days()) {
                String cls = d.pct() >= 95 ? "good" : d.pct() >= 80 ? "warn" : "bad";
                rows.append("<td class='r ").append(cls).append("'>").append(d.pct()).append("%</td>");
            }
            rows.append("<td class='r'><b>").append(r.avgPct()).append("%</b></td></tr>");
        }
        StringBuilder dayHead = new StringBuilder();
        if (!report.rows().isEmpty()) {
            for (ReportService.UptimeDay d : report.rows().get(0).days()) {
                dayHead.append("<th class='r'>").append(d.date().substring(5)).append("</th>");
            }
        }
        String html = pdfShell("Screen Uptime Report", from, to,
                redFlags
                        + "<h2>Daily online % per screen</h2>"
                        + "<table><thead><tr><th>Screen</th><th>Store</th><th>City</th>" + dayHead
                        + "<th class='r'>Avg</th></tr></thead><tbody>" + rows + "</tbody></table>");
        return renderPdf(html);
    }

    private String pdfShell(String title, LocalDate from, LocalDate to, String body) {
        String shell = """
                <html><head><style>
                  @page { size: A4 landscape; margin: 14mm; }
                  body { font-family: Helvetica, Arial, sans-serif; color: #16233F; font-size: 9px; }
                  .band { background: #16233F; color: white; padding: 14px 18px; border-bottom: 5px solid #F6A821; }
                  .band .logo { font-size: 20px; font-weight: bold; }
                  .band .logo span { color: #F6A821; }
                  .band .title { font-size: 13px; margin-top: 3px; color: #F6A821; font-weight: bold; }
                  .meta { color: #6B7280; margin: 8px 0 14px 0; font-size: 9px; }
                  .stats { margin: 0 0 12px 0; }
                  .stats div { display: inline-block; background: #FAF8F4; border: 1px solid #E7E2D8; border-radius: 6px; padding: 7px 12px; margin-right: 8px; }
                  h2 { font-size: 12px; margin: 14px 0 6px 0; }
                  table { width: 100%; border-collapse: collapse; }
                  th { background: #F6A821; color: #16233F; text-align: left; padding: 5px 7px; font-size: 9px; }
                  th.r, td.r { text-align: right; }
                  td { border-bottom: 1px solid #EDE9E0; padding: 4.5px 7px; }
                  tr:nth-child(even) td { background: #FBFAF7; }
                  .good { color: #15803D; } .warn { color: #B45309; } .bad { color: #B91C1C; font-weight: bold; }
                </style></head><body>
                  <div class='band'>
                    <div class='logo'>screen<span>Pilot</span> · Digital Signage</div>
                    <div class='title'>@@TITLE@@</div>
                  </div>
                  <p class='meta'>Period: @@FROM@@ to @@TO@@ (IST) · Generated @@GENERATED@@ IST · Generated by ScreenPilot</p>
                  @@BODY@@
                </body></html>
                """;
        return shell
                .replace("@@TITLE@@", escape(title))
                .replace("@@FROM@@", from.toString())
                .replace("@@TO@@", to.toString())
                .replace("@@GENERATED@@", ZonedDateTime.now(TimeUtil.IST).format(TS))
                .replace("@@BODY@@", body);
    }

    private byte[] renderPdf(String html) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }

    private String td(String v) {
        return "<td>" + v + "</td>";
    }

    private String tdRight(String v) {
        return "<td class='r'>" + v + "</td>";
    }

    private String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private String escape(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
