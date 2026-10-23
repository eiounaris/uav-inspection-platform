package com.uav.inspection.evidence.finalization;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Bounded container checks over the complete file, plus decoded image validation. No external process runs. */
final class FileFormatValidator {
    private static final long MAX_IMAGE_PIXELS=25_000_000;
    private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    private static final Pattern PDF_END=Pattern.compile("startxref\\s+(\\d+)\\s+%%EOF\\s*\\z");

    void validate(Path file,String contentType) throws IOException {
        try {
            switch(contentType) {
                case "image/png" -> { png(file); image(file,"png"); }
                case "image/jpeg" -> { jpeg(file); image(file,"JPEG"); }
                case "application/pdf" -> pdf(file);
                case "video/mp4" -> mp4(file);
                default -> invalid();
            }
        } catch (InvalidFile failure) {
            throw failure;
        } catch (javax.imageio.IIOException | java.io.EOFException failure) {
            throw new InvalidFile("FILE_FORMAT_INVALID");
        }
    }

    private void png(Path file) throws IOException {
        long remaining=Files.size(file);
        try(var input=new DataInputStream(Files.newInputStream(file))) {
            if(!Arrays.equals(input.readNBytes(8),PNG)) invalid();
            remaining-=8;
            boolean header=false,data=false,dataEnded=false;
            byte[] buffer=new byte[32768];
            while(remaining>0) {
                if(remaining<12) invalid();
                long length=Integer.toUnsignedLong(input.readInt());
                byte[] type=input.readNBytes(4);
                String name=new String(type,StandardCharsets.US_ASCII);
                if(length>remaining-12 || !name.matches("[A-Za-z]{4}")) invalid();
                if(!header && (!"IHDR".equals(name) || length!=13)) invalid();
                if(header && "IHDR".equals(name)) invalid();
                if("IDAT".equals(name)) { if(dataEnded) invalid(); data=true; }
                else if(data) dataEnded=true;
                CRC32 crc=new CRC32(); crc.update(type);
                long rest=length;
                while(rest>0) {
                    int count=input.read(buffer,0,(int)Math.min(rest,buffer.length));
                    if(count<0) invalid();
                    crc.update(buffer,0,count); rest-=count;
                }
                if(crc.getValue()!=Integer.toUnsignedLong(input.readInt())) invalid();
                remaining-=length+12; header=true;
                if("IEND".equals(name)) { if(length!=0 || !data || remaining!=0) invalid(); return; }
            }
            invalid();
        }
    }

    private void jpeg(Path file) throws IOException {
        try(var input=new RandomAccessFile(file.toFile(),"r")) {
            if(input.readUnsignedShort()!=0xffd8) invalid();
            boolean frame=false,scan=false;
            while(input.getFilePointer()<input.length()) {
                if(input.readUnsignedByte()!=0xff) invalid();
                int marker;
                do { marker=input.readUnsignedByte(); } while(marker==0xff);
                if(marker==0xd9) { if(!frame || !scan || input.getFilePointer()!=input.length()) invalid(); return; }
                if(marker==0 || marker==0xd8 || marker==1 || marker>=0xd0 && marker<=0xd7) invalid();
                int length=input.readUnsignedShort();
                if(length<2 || length-2>input.length()-input.getFilePointer()) invalid();
                if(marker==0xc0 || marker==0xc1 || marker==0xc2) frame=true;
                input.seek(input.getFilePointer()+length-2);
                if(marker==0xda) {
                    scan=true;
                    while(true) {
                        int value=input.readUnsignedByte();
                        if(value!=0xff) continue;
                        long start=input.getFilePointer()-1;
                        int next;
                        do { next=input.readUnsignedByte(); } while(next==0xff);
                        if(next==0 || next>=0xd0 && next<=0xd7) continue;
                        input.seek(start); break;
                    }
                }
            }
            invalid();
        }
    }

    private void image(Path file,String format) throws IOException {
        try(var input=new FileImageInputStream(file.toFile())) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) invalid();
            ImageReader reader=readers.next();
            try {
                if(!format.equalsIgnoreCase(reader.getFormatName())) invalid();
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>MAX_IMAGE_PIXELS) invalid();
                AtomicBoolean warning=new AtomicBoolean();
                reader.addIIOReadWarningListener((source,message)->warning.set(true));
                var image=reader.read(0);
                if(image==null || warning.get()) invalid();
                image.flush();
            } finally { reader.dispose(); }
        }
    }

    private void pdf(Path file) throws IOException {
        try(var input=new RandomAccessFile(file.toFile(),"r")) {
            if(input.length()<32) invalid();
            byte[] header=new byte[8]; input.readFully(header);
            if(!new String(header,StandardCharsets.US_ASCII).matches("%PDF-[12]\\.[0-9]")) invalid();
            int count=(int)Math.min(4096,input.length());
            byte[] tail=new byte[count]; input.seek(input.length()-count); input.readFully(tail);
            var matcher=PDF_END.matcher(new String(tail,StandardCharsets.US_ASCII));
            if(!matcher.find()) invalid();
            long xref;
            try { xref=Long.parseLong(matcher.group(1)); } catch(NumberFormatException failure) { invalid(); return; }
            if(xref<8 || xref>=input.length()-count+matcher.start()) invalid();
            input.seek(xref); byte[] prefix=new byte[(int)Math.min(64,input.length()-xref)]; input.readFully(prefix);
            String value=new String(prefix,StandardCharsets.US_ASCII);
            if(!value.startsWith("xref") && !Pattern.compile("\\d+\\s+\\d+\\s+obj\\b").matcher(value).lookingAt()) invalid();
        }
    }

    private void mp4(Path file) throws IOException {
        try(var input=new RandomAccessFile(file.toFile(),"r")) {
            boolean type=false,movie=false,data=false;
            int boxes=0;
            while(input.getFilePointer()<input.length()) {
                if(++boxes>100000) invalid();
                Box box=box(input,input.length());
                switch(box.type()) {
                    case "ftyp" -> { if(type || box.end()-box.content()<8 || (box.end()-box.content())%4!=0) invalid(); type=true; }
                    case "moov" -> { if(movie) invalid(); movie=true; movie(input,box.end()); }
                    case "mdat" -> { if(box.end()==box.content()) invalid(); data=true; }
                    default -> { }
                }
                input.seek(box.end());
            }
            if(!type || !movie || !data) invalid();
        }
    }

    private void movie(RandomAccessFile input,long end) throws IOException {
        boolean header=false,track=false; int count=0;
        while(input.getFilePointer()<end) {
            if(++count>100000) invalid();
            Box box=box(input,end);
            if("mvhd".equals(box.type())) { if(box.end()-box.content()<20) invalid(); header=true; }
            if("trak".equals(box.type())) { if(box.end()-box.content()<8) invalid(); track=true; }
            input.seek(box.end());
        }
        if(!header || !track) invalid();
    }

    private Box box(RandomAccessFile input,long limit) throws IOException {
        long start=input.getFilePointer();
        if(limit-start<8) invalid();
        long size=Integer.toUnsignedLong(input.readInt());
        byte[] bytes=new byte[4]; input.readFully(bytes);
        String type=new String(bytes,StandardCharsets.US_ASCII);
        if(!type.matches("[A-Za-z0-9 ]{4}")) invalid();
        if(size==1) { if(limit-input.getFilePointer()<8) invalid(); size=input.readLong(); }
        else if(size==0) size=limit-start;
        long header=input.getFilePointer()-start;
        if(size<header || size>limit-start) invalid();
        return new Box(type,input.getFilePointer(),start+size);
    }

    private static void invalid() { throw new InvalidFile("FILE_FORMAT_INVALID"); }
    record Box(String type,long content,long end) {}
    static final class InvalidFile extends RuntimeException {
        private final String code;
        InvalidFile(String code) { super(code); this.code=code; }
        String code() { return code; }
    }
}
