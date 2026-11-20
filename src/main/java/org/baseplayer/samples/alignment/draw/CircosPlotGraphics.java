package org.baseplayer.samples.alignment.draw;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;

/**
 * Shared drawing surface for on-screen Circos canvas and vector PDF export.
 * Coordinates match JavaFX canvas space (origin top-left, Y down).
 */
interface CircosPlotGraphics {

    void fillRect(Color color, double x, double y, double w, double h);

    void setStroke(Color color);

    void setFill(Color color);

    void setLineWidth(double width);

    void setLineCapButt();

    void setLineCapRound();

    /**
     * Stroke an open circular arc using JavaFX {@link GraphicsContext#strokeArc}
     * angle conventions (degrees; 0 = east; positive = counterclockwise in FX).
     */
    void strokeOpenArc(double x, double y, double w, double h,
                       double startDeg, double extentDeg);

    void beginPath();

    void moveTo(double x, double y);

    void lineTo(double x, double y);

    void quadraticCurveTo(double cpx, double cpy, double x, double y);

    void stroke();

    void fillOval(double x, double y, double w, double h);

    void setFont(Font font);

    void fillText(String text, double x, double y);

    /** JavaFX canvas backend. */
    final class Fx implements CircosPlotGraphics {
        private final GraphicsContext g;

        Fx(GraphicsContext g) {
            this.g = g;
        }

        @Override
        public void fillRect(Color color, double x, double y, double w, double h) {
            g.setFill(color);
            g.fillRect(x, y, w, h);
        }

        @Override
        public void setStroke(Color color) {
            g.setStroke(color);
        }

        @Override
        public void setFill(Color color) {
            g.setFill(color);
        }

        @Override
        public void setLineWidth(double width) {
            g.setLineWidth(width);
        }

        @Override
        public void setLineCapButt() {
            g.setLineCap(StrokeLineCap.BUTT);
        }

        @Override
        public void setLineCapRound() {
            g.setLineCap(StrokeLineCap.ROUND);
        }

        @Override
        public void strokeOpenArc(double x, double y, double w, double h,
                                  double startDeg, double extentDeg) {
            g.strokeArc(x, y, w, h, startDeg, extentDeg, ArcType.OPEN);
        }

        @Override
        public void beginPath() {
            g.beginPath();
        }

        @Override
        public void moveTo(double x, double y) {
            g.moveTo(x, y);
        }

        @Override
        public void lineTo(double x, double y) {
            g.lineTo(x, y);
        }

        @Override
        public void quadraticCurveTo(double cpx, double cpy, double x, double y) {
            g.quadraticCurveTo(cpx, cpy, x, y);
        }

        @Override
        public void stroke() {
            g.stroke();
        }

        @Override
        public void fillOval(double x, double y, double w, double h) {
            g.fillOval(x, y, w, h);
        }

        @Override
        public void setFont(Font font) {
            g.setFont(font);
        }

        @Override
        public void fillText(String text, double x, double y) {
            g.fillText(text, x, y);
        }
    }

    /**
     * JDK-only vector PDF content builder (no PDFBox). Emits path/text operators
     * into a content stream; {@link #writeTo(File)} wraps it as a one-page PDF
     * with Helvetica Standard-14 fonts.
     */
    final class Pdf implements CircosPlotGraphics {
        private final double pageSize;
        private final StringBuilder content = new StringBuilder(64 * 1024);
        /** ExtGState name → dict body (without outer << >>). */
        private final Map<String, String> extGStates = new LinkedHashMap<>();
        private boolean bold;
        private float fontSize = 12f;
        private boolean pathOpen;
        private double pathX;
        private double pathY;
        private float strokeA = 1f;
        private float fillA = 1f;

        Pdf(double pageSize) {
            this.pageSize = pageSize;
            // Match JavaFX canvas space: origin top-left, Y down.
            append("1 0 0 -1 0 %s cm\n", num(pageSize));
        }

        @Override
        public void fillRect(Color color, double x, double y, double w, double h) {
            applyNonStroking(color);
            append("%s %s %s %s re f\n", num(x), num(y), num(w), num(h));
        }

        @Override
        public void setStroke(Color color) {
            applyStroking(color);
        }

        @Override
        public void setFill(Color color) {
            applyNonStroking(color);
        }

        @Override
        public void setLineWidth(double width) {
            append("%s w\n", num(width));
        }

        @Override
        public void setLineCapButt() {
            append("0 J\n");
        }

        @Override
        public void setLineCapRound() {
            append("1 J\n");
        }

        @Override
        public void strokeOpenArc(double x, double y, double w, double h,
                                  double startDeg, double extentDeg) {
            pathOpen = false;
            appendArc(x + w / 2.0, y + h / 2.0, w / 2.0, h / 2.0, startDeg, extentDeg);
            append("S\n");
            pathOpen = false;
        }

        @Override
        public void beginPath() {
            pathOpen = false;
        }

        @Override
        public void moveTo(double x, double y) {
            append("%s %s m\n", num(x), num(y));
            pathOpen = true;
            pathX = x;
            pathY = y;
        }

        @Override
        public void lineTo(double x, double y) {
            append("%s %s l\n", num(x), num(y));
            pathX = x;
            pathY = y;
        }

        @Override
        public void quadraticCurveTo(double cpx, double cpy, double x, double y) {
            double x0 = pathX;
            double y0 = pathY;
            double cp1x = x0 + 2.0 / 3.0 * (cpx - x0);
            double cp1y = y0 + 2.0 / 3.0 * (cpy - y0);
            double cp2x = x + 2.0 / 3.0 * (cpx - x);
            double cp2y = y + 2.0 / 3.0 * (cpy - y);
            append("%s %s %s %s %s %s c\n",
                    num(cp1x), num(cp1y), num(cp2x), num(cp2y), num(x), num(y));
            pathX = x;
            pathY = y;
        }

        @Override
        public void stroke() {
            append("S\n");
            pathOpen = false;
        }

        @Override
        public void fillOval(double x, double y, double w, double h) {
            pathOpen = false;
            appendArc(x + w / 2.0, y + h / 2.0, w / 2.0, h / 2.0, 0, 360);
            append("f\n");
            pathOpen = false;
        }

        @Override
        public void setFont(Font font) {
            String name = font != null && font.getName() != null ? font.getName().toLowerCase() : "";
            boolean isBold = name.contains("bold")
                    || (font != null && font.getStyle() != null && font.getStyle().toLowerCase().contains("bold"));
            bold = isBold;
            fontSize = font != null ? (float) font.getSize() : 12f;
        }

        @Override
        public void fillText(String text, double x, double y) {
            if (text == null || text.isEmpty()) return;
            String safe = escapePdfString(sanitizePdfText(text));
            String fontRes = bold ? "/HelvB" : "/Helv";
            // Global CTM is Y-flipped; un-flip through the baseline so text is upright.
            append("q\n1 0 0 -1 0 %s cm\n", num(2 * y));
            append("BT %s %s Tf %s %s Td (%s) Tj ET\n",
                    fontRes, num(fontSize), num(x), num(y), safe);
            append("Q\n");
        }

        /** Write a complete one-page vector PDF for the recorded content. */
        void writeTo(File target) throws IOException {
            byte[] stream = content.toString().getBytes(StandardCharsets.US_ASCII);

            StringBuilder extGs = new StringBuilder();
            for (Map.Entry<String, String> e : extGStates.entrySet()) {
                extGs.append('/').append(e.getKey()).append(" << ").append(e.getValue()).append(" >> ");
            }

            String resources = String.format(Locale.ROOT,
                    "/Font << /Helv << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> "
                            + "/HelvB << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >> >> "
                            + "/ExtGState << %s>>",
                    extGs);

            ByteArrayOutputStream out = new ByteArrayOutputStream(stream.length + 4096);
            List<Integer> offsets = new ArrayList<>(6);
            writeAscii(out, "%PDF-1.4\n");

            offsets.add(out.size());
            writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            offsets.add(out.size());
            writeAscii(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

            offsets.add(out.size());
            writeAscii(out, String.format(Locale.ROOT,
                    "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %s %s] "
                            + "/Contents 4 0 R /Resources << %s >> >>\nendobj\n",
                    num(pageSize), num(pageSize), resources));

            offsets.add(out.size());
            writeAscii(out, String.format(Locale.ROOT,
                    "4 0 obj\n<< /Length %d >>\nstream\n", stream.length));
            out.write(stream);
            writeAscii(out, "\nendstream\nendobj\n");

            int xrefPos = out.size();
            writeAscii(out, String.format(Locale.ROOT, "xref\n0 %d\n", offsets.size() + 1));
            writeAscii(out, "0000000000 65535 f \n");
            for (int off : offsets) {
                writeAscii(out, String.format(Locale.ROOT, "%010d 00000 n \n", off));
            }
            writeAscii(out, String.format(Locale.ROOT,
                    "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n",
                    offsets.size() + 1, xrefPos));

            try (OutputStream fos = Files.newOutputStream(target.toPath())) {
                out.writeTo(fos);
            }
        }

        private void applyStroking(Color color) {
            strokeA = (float) clamp01(color.getOpacity());
            applyAlphaGs();
            append("%s %s %s RG\n", num(color.getRed()), num(color.getGreen()), num(color.getBlue()));
        }

        private void applyNonStroking(Color color) {
            fillA = (float) clamp01(color.getOpacity());
            applyAlphaGs();
            append("%s %s %s rg\n", num(color.getRed()), num(color.getGreen()), num(color.getBlue()));
        }

        private void applyAlphaGs() {
            int s = Math.round(strokeA * 1000f);
            int f = Math.round(fillA * 1000f);
            String name = "GS" + s + "F" + f;
            extGStates.computeIfAbsent(name, k -> String.format(Locale.ROOT,
                    "/Type /ExtGState /CA %s /ca %s", num(strokeA), num(fillA)));
            append("/%s gs\n", name);
        }

        private void appendArc(double cx, double cy, double rx, double ry,
                               double startDeg, double extentDeg) {
            if (Math.abs(extentDeg) < 1e-6) return;

            double start = Math.toRadians(startDeg);
            double extent = Math.toRadians(extentDeg);
            int segments = Math.max(1, (int) Math.ceil(Math.abs(extentDeg) / 90.0));
            double delta = extent / segments;

            double angle = start;
            double x0 = cx + rx * Math.cos(angle);
            double y0 = cy - ry * Math.sin(angle);

            if (pathOpen) {
                append("%s %s l\n", num(x0), num(y0));
            } else {
                append("%s %s m\n", num(x0), num(y0));
                pathOpen = true;
            }
            pathX = x0;
            pathY = y0;

            for (int i = 0; i < segments; i++) {
                double a1 = angle;
                double a2 = angle + delta;
                double alpha = Math.tan((a2 - a1) / 4.0) * 4.0 / 3.0;

                double cos1 = Math.cos(a1);
                double sin1 = Math.sin(a1);
                double cos2 = Math.cos(a2);
                double sin2 = Math.sin(a2);

                double x1 = cx + rx * cos1;
                double y1 = cy - ry * sin1;
                double x2 = cx + rx * cos2;
                double y2 = cy - ry * sin2;

                double dx1 = -rx * sin1;
                double dy1 = -ry * cos1;
                double dx2 = -rx * sin2;
                double dy2 = -ry * cos2;

                append("%s %s %s %s %s %s c\n",
                        num(x1 + alpha * dx1), num(y1 + alpha * dy1),
                        num(x2 - alpha * dx2), num(y2 - alpha * dy2),
                        num(x2), num(y2));
                pathX = x2;
                pathY = y2;
                angle = a2;
            }
        }

        private void append(String fmt, Object... args) {
            content.append(String.format(Locale.ROOT, fmt, args));
        }

        private static String num(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
            // Trim trailing zeros for slightly smaller streams.
            String s = String.format(Locale.ROOT, "%.4f", v);
            int dot = s.indexOf('.');
            if (dot < 0) return s;
            int end = s.length();
            while (end > dot + 1 && s.charAt(end - 1) == '0') end--;
            if (end > dot && s.charAt(end - 1) == '.') end--;
            return s.substring(0, end);
        }

        private static double clamp01(double v) {
            return Math.max(0, Math.min(1, v));
        }

        private static String sanitizePdfText(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 32 && c <= 126) {
                    sb.append(c);
                } else if (c == 0x2013 || c == 0x2014) {
                    sb.append('-');
                } else if (c == 0x2022) {
                    sb.append('*');
                } else {
                    sb.append('?');
                }
            }
            return sb.toString();
        }

        private static String escapePdfString(String text) {
            StringBuilder sb = new StringBuilder(text.length() + 8);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(' || c == ')' || c == '\\') {
                    sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
