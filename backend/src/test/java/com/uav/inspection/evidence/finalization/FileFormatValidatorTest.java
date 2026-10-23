package com.uav.inspection.evidence.finalization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FileFormatValidatorTest {
    @TempDir Path directory;
    private final FileFormatValidator validator=new FileFormatValidator();

    @Test
    void validPngAndJpegAreDecoded() throws Exception {
        for(String format:new String[]{"png","jpeg"}) {
            Path image=directory.resolve("image."+format);
            assertTrue(ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,image.toFile()));
            assertDoesNotThrow(()->validator.validate(image,"image/"+format));
        }
    }

    @Test
    void pngCorruptionAndAFileTruncatedAfterItsValidSignatureAreRejected() throws Exception {
        Path image=directory.resolve("image.png");
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",image.toFile());
        byte[] data=Files.readAllBytes(image);
        data[29]^=1;
        Files.write(image,data);
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(image,"image/png"));
        Files.write(image,Arrays.copyOf(data,8));
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(image,"image/png"));
    }

    @Test
    void jpegRequiresAnActualEndMarkerAndRejectsAnUnrelatedDeclaredType() throws Exception {
        Path image=directory.resolve("image.jpeg");
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"jpeg",image.toFile());
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(image,"image/png"));
        byte[] data=Files.readAllBytes(image);
        Files.write(image,Arrays.copyOf(data,data.length-2));
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(image,"image/jpeg"));
    }

    @Test
    void pdfRequiresItsFinalTrailerAndAResolvableCrossReferenceOffset() throws Exception {
        Path pdf=directory.resolve("document.pdf");
        String content="%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n";
        int offset=content.length();
        Files.writeString(pdf,content+"xref\n0 2\n0000000000 65535 f \n0000000009 00000 n \n"
                +"trailer\n<< /Root 1 0 R /Size 2 >>\nstartxref\n"+offset+"\n%%EOF\n",StandardCharsets.US_ASCII);
        assertDoesNotThrow(()->validator.validate(pdf,"application/pdf"));
        Files.writeString(pdf,content+"startxref\n999999\n%%EOF\n");
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(pdf,"application/pdf"));
        Files.writeString(pdf,"%PDF-1.7\n");
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(pdf,"application/pdf"));
    }

    @Test
    void mp4RequiresCompleteMovieAndMediaBoxesWithinTheFileBoundary() throws Exception {
        Path video=directory.resolve("video.mp4");
        byte[] moov=concat(box("mvhd",new byte[20]),box("trak",box("tkhd",new byte[16])));
        byte[] bytes=concat(box("ftyp","isom0000".getBytes(StandardCharsets.US_ASCII)),box("moov",moov),box("mdat",new byte[4]));
        Files.write(video,bytes);
        assertDoesNotThrow(()->validator.validate(video,"video/mp4"));
        Files.write(video,Arrays.copyOf(bytes,bytes.length-1));
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(video,"video/mp4"));
        Files.write(video,box("ftyp","isom0000".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(FileFormatValidator.InvalidFile.class,()->validator.validate(video,"video/mp4"));
    }

    private static byte[] box(String type,byte[] body) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)) { output.writeInt(body.length+8); output.writeBytes(type); output.write(body); }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        var bytes=new ByteArrayOutputStream();
        for(byte[] part:parts) bytes.write(part);
        return bytes.toByteArray();
    }
}
