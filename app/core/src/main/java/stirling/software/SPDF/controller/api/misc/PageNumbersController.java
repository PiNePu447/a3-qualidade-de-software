package stirling.software.SPDF.controller.api.misc;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.misc.AddPageNumbersRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

/** Adiciona números de página em um PDF. */
@MiscApi
@RequiredArgsConstructor
public class PageNumbersController {

  private static final float MARGIN_SMALL = 0.02f;
  private static final float MARGIN_MEDIUM = 0.035f;
  private static final float MARGIN_LARGE = 0.05f;
  private static final float MARGIN_XLARGE = 0.075f;
  private static final float FONT_UNITS = 1000f;
  private static final float HALF = 2f;
  private static final int MIN_POSITION = 1;
  private static final int MAX_POSITION = 9;
  private static final int GRID = 3;
  private static final int LEFT_OR_TOP = 1;
  private static final int CENTER = 2;
  private static final String ALL_PAGES = "all";
  private static final String DEFAULT_TEXT = "{n}";

  private final CustomPDFDocumentFactory pdfDocumentFactory;
  private final TempFileManager tempFileManager;

  /** Endpoint que recebe o PDF e devolve o arquivo com a numeração. */
  @AutoJobPostMapping(
      value = "/add-page-numbers",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      resourceWeight = ResourceWeight.SMALL_WEIGHT)
  @StandardPdfResponse
  @ToolIO(produces = ToolFormat.PDF)
  @Operation(
      summary = "Add page numbers to a PDF document",
      description = "This operation takes an input PDF file and adds page numbers to it.")
  public ResponseEntity<Resource> addPageNumbers(@ModelAttribute AddPageNumbersRequest request)
      throws IOException {
    MultipartFile file = request.getFileInput();
    String formatN = numberFormat(request.getZeroPad());
    Color color = parseColor(request.getFontColor());
    float fontSize = request.getFontSize();
    String template = orDefault(request.getCustomText(), DEFAULT_TEXT);
    String fileName =
        GeneralUtils.removeExtension(Filenames.toSimpleFileName(file.getOriginalFilename()));

    try (PDDocument document = pdfDocumentFactory.load(file)) {
      float marginFactor = marginFactor(request.getCustomMargin());
      int pos = Math.max(MIN_POSITION, Math.min(MAX_POSITION, request.getPosition()));
      int total = document.getNumberOfPages();
      List<Integer> pages =
          GeneralUtils.parsePageList(
              orDefault(request.getPagesToNumber(), ALL_PAGES).split(","), total);

      int pageNumber = request.getStartingNumber();
      for (int i : pages) {
        String text =
            template
                .replace("{n}", String.format(formatN, pageNumber))
                .replace("{total}", String.valueOf(total))
                .replace("{filename}", fileName);
        PDType1Font font = resolveFont(request.getFontType());
        drawText(document, document.getPage(i), text, font, fontSize, color, pos, marginFactor);
        pageNumber++;
      }
      return save(document, file.getOriginalFilename());
    }
  }

  private static String numberFormat(int zeroPad) {
    return zeroPad > 0 ? String.format("%%0%dd", zeroPad) : "%d";
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static Color parseColor(String fontColor) {
    if (fontColor == null || fontColor.trim().isEmpty()) {
      return Color.BLACK;
    }
    try {
      return Color.decode(fontColor);
    } catch (NumberFormatException e) {
      return Color.BLACK;
    }
  }

  private static float marginFactor(String customMargin) {
    String key = customMargin == null ? "" : customMargin.toLowerCase(Locale.ROOT);
    return switch (key) {
      case "small" -> MARGIN_SMALL;
      case "large" -> MARGIN_LARGE;
      case "x-large" -> MARGIN_XLARGE;
      default -> MARGIN_MEDIUM;
    };
  }

  private static PDType1Font resolveFont(String fontType) {
    String key = fontType == null ? "" : fontType.toLowerCase(Locale.ROOT);
    return switch (key) {
      case "courier" -> new PDType1Font(Standard14Fonts.FontName.COURIER);
      case "times" -> new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
      default -> new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    };
  }

  private static float computeX(PDRectangle box, float margin, int col, float textWidth) {
    if (col == LEFT_OR_TOP) {
      return box.getLowerLeftX() + margin * box.getWidth();
    }
    if (col == CENTER) {
      return box.getLowerLeftX() + box.getWidth() / HALF - textWidth / HALF;
    }
    return box.getUpperRightX() - margin * box.getWidth() - textWidth;
  }

  private static float computeY(
      PDRectangle box, float margin, int row, float ascent, float descent) {
    if (row == LEFT_OR_TOP) {
      return box.getUpperRightY() - margin * box.getHeight() - ascent;
    }
    if (row == CENTER) {
      return box.getLowerLeftY() + box.getHeight() / HALF - (ascent + descent) / HALF;
    }
    return box.getLowerLeftY() + margin * box.getHeight();
  }

  private static void drawText(
      PDDocument document,
      PDPage page,
      String text,
      PDType1Font font,
      float fontSize,
      Color color,
      int pos,
      float margin)
      throws IOException {
    float textWidth = font.getStringWidth(text) / FONT_UNITS * fontSize;
    float ascent = font.getFontDescriptor().getAscent() / FONT_UNITS * fontSize;
    float descent = font.getFontDescriptor().getDescent() / FONT_UNITS * fontSize;
    PDRectangle box = page.getMediaBox();
    float x = computeX(box, margin, (pos - 1) % GRID + 1, textWidth);
    float y = computeY(box, margin, (pos - 1) / GRID + 1, ascent, descent);
    try (PDPageContentStream stream =
        new PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
      stream.beginText();
      stream.setFont(font, fontSize);
      stream.setNonStrokingColor(color);
      stream.newLineAtOffset(x, y);
      stream.showText(text);
      stream.endText();
    }
  }

  private ResponseEntity<Resource> save(PDDocument document, String originalName)
      throws IOException {
    TempFile tempOut = tempFileManager.createManagedTempFile(".pdf");
    try {
      document.save(tempOut.getFile());
    } catch (IOException e) {
      tempOut.close();
      throw e;
    }
    return WebResponseUtils.pdfFileToWebResponse(
        tempOut, GeneralUtils.generateFilename(originalName, "_page_numbers_added.pdf"));
  }
}
