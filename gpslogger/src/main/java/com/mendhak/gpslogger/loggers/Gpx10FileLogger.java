/*
*    This file is part of GPSLogger for Android.
*
*    GPSLogger for Android is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 2 of the License, or
*    (at your option) any later version.
*
*    GPSLogger for Android is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.mendhak.gpslogger.loggers;

import android.location.Location;
import com.mendhak.gpslogger.common.RejectionHandler;
import com.mendhak.gpslogger.common.Utilities;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class Gpx10FileLogger implements IFileLogger {
    protected final static Object lock = new Object();

    private final static ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(128), new RejectionHandler());
    private File gpxFile = null;
    private final boolean addNewTrackSegment;
    private final int satelliteCount;
    protected final String name = "GPX";
    private static final org.slf4j.Logger tracer = LoggerFactory.getLogger(Gpx10FileLogger.class.getSimpleName());

    Gpx10FileLogger(File gpxFile, boolean addNewTrackSegment, int satelliteCount) {
        this.gpxFile = gpxFile;
        this.addNewTrackSegment = addNewTrackSegment;
        this.satelliteCount = satelliteCount;
    }


    public void Write(Location loc) throws Exception {
        long time = loc.getTime();
        if (time <= 0) {
            time = System.currentTimeMillis();
        }
        String dateTimeString = Utilities.GetIsoDateTime(new Date(time));

        Gpx10WriteHandler writeHandler = new Gpx10WriteHandler(dateTimeString, gpxFile, loc, addNewTrackSegment, satelliteCount);
        tracer.debug(String.format("There are currently %s tasks waiting on the GPX10 EXECUTOR.", EXECUTOR.getQueue().size()));
        EXECUTOR.execute(writeHandler);
    }

    public void Annotate(String description, Location loc) throws Exception {

        long time = loc.getTime();
        if (time <= 0) {
            time = System.currentTimeMillis();
        }
        String dateTimeString = Utilities.GetIsoDateTime(new Date(time));

        Gpx10AnnotateHandler annotateHandler = new Gpx10AnnotateHandler(description, gpxFile, loc, dateTimeString);
        tracer.debug(String.format("There are currently %s tasks waiting on the GPX10 EXECUTOR.", EXECUTOR.getQueue().size()));
        EXECUTOR.execute(annotateHandler);

    }

    @Override
    public String getName() {
        return name;
    }


}

class Gpx10AnnotateHandler implements Runnable {
    private static final org.slf4j.Logger tracer = LoggerFactory.getLogger(Gpx10AnnotateHandler.class.getSimpleName());
    String description;
    File gpxFile;
    Location loc;
    String dateTimeString;

    public Gpx10AnnotateHandler(String description, File gpxFile, Location loc, String dateTimeString) {
        this.description = description;
        this.gpxFile = gpxFile;
        this.loc = loc;
        this.dateTimeString = dateTimeString;
    }

    @Override
    public void run() {

        synchronized (Gpx10FileLogger.lock) {
            if (!gpxFile.exists()) {
                return;
            }

            if (!gpxFile.exists()) {
                return;
            }

            int startPosition = 336;

            String wpt = GetWaypointXml(loc, dateTimeString, description);

            try {

                //Write to a temp file, delete original file, move temp to original
                File gpxTempFile = new File(gpxFile.getAbsolutePath() + ".tmp");

                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(gpxFile));
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(gpxTempFile));

                int written = 0;
                int readSize;
                byte[] buffer = new byte[startPosition];
                while ((readSize = bis.read(buffer)) > 0) {
                    bos.write(buffer, 0, readSize);
                    written += readSize;

                    System.out.println(written);

                    if (written == startPosition) {
                        bos.write(wpt.getBytes());
                        buffer = new byte[20480];
                    }

                }

                bis.close();
                bos.close();

                gpxFile.delete();
                gpxTempFile.renameTo(gpxFile);

                tracer.debug("Finished annotation to GPX10 File");
            } catch (Exception e) {
                tracer.error("Gpx10FileLogger.Annotate", e);
            }

        }
    }

    String GetWaypointXml(Location loc, String dateTimeString, String description) {

        StringBuilder waypoint = new StringBuilder();

        waypoint.append("\n<wpt lat=\"")
                .append(String.valueOf(loc.getLatitude()))
                .append("\" lon=\"")
                .append(String.valueOf(loc.getLongitude()))
                .append("\">");

        if (loc.hasAltitude()) {
            waypoint.append("<ele>").append(String.valueOf(loc.getAltitude())).append("</ele>");
        }

        waypoint.append("<time>").append(dateTimeString).append("</time>");
        waypoint.append("<name>").append(description).append("</name>");

        waypoint.append("<src>").append(loc.getProvider()).append("</src>");
        waypoint.append("</wpt>\n");

        return waypoint.toString();
    }
}


class Gpx10WriteHandler implements Runnable {
    private static final org.slf4j.Logger tracer = LoggerFactory.getLogger(Gpx10WriteHandler.class.getSimpleName());
    String dateTimeString;
    Location loc;
    private File gpxFile = null;
    private boolean addNewTrackSegment;
    private int satelliteCount;

    public Gpx10WriteHandler(String dateTimeString, File gpxFile, Location loc, boolean addNewTrackSegment, int satelliteCount) {
        this.dateTimeString = dateTimeString;
        this.addNewTrackSegment = addNewTrackSegment;
        this.gpxFile = gpxFile;
        this.loc = loc;
        this.satelliteCount = satelliteCount;
    }


    @Override
    public void run() {
        synchronized (Gpx10FileLogger.lock) {

            try {
                if (!gpxFile.exists()) {
                    gpxFile.createNewFile();

                    FileOutputStream initialWriter = new FileOutputStream(gpxFile, true);
                    BufferedOutputStream initialOutput = new BufferedOutputStream(initialWriter);

                    StringBuilder initialXml = new StringBuilder();
                    initialXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
                    initialXml.append("<gpx version=\"1.0\" creator=\"GPSLogger - http://gpslogger.mendhak.com/\" ");
                    initialXml.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
                    initialXml.append("xmlns=\"http://www.topografix.com/GPX/1/0\" ");
                    initialXml.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/0 ");
                    initialXml.append("http://www.topografix.com/GPX/1/0/gpx.xsd\">");
                    initialXml.append("<time>").append(dateTimeString).append("</time>").append("<trk></trk></gpx>");
                    initialOutput.write(initialXml.toString().getBytes());
                    initialOutput.flush();
                    initialOutput.close();

                    //New file, so new segment.
                    addNewTrackSegment = true;
                }

                int offsetFromEnd = (addNewTrackSegment) ? 12 : 21;
                long startPosition = gpxFile.length() - offsetFromEnd;
                String trackPoint = GetTrackPointXml(loc, dateTimeString);

                RandomAccessFile raf = new RandomAccessFile(gpxFile, "rw");
                raf.seek(startPosition);
                raf.write(trackPoint.getBytes());
                raf.close();
                tracer.debug("Finished writing to GPX10 file");

            } catch (Exception e) {
                tracer.error("Gpx10FileLogger.Write", e);
            }

        }

    }

    String GetTrackPointXml(Location loc, String dateTimeString) {

        StringBuilder track = new StringBuilder();
        Boolean bicyclingoverwritten = false;

        if (addNewTrackSegment) {
            track.append("<trkseg>");
        }

        track.append("<trkpt lat=\"")
                .append(String.valueOf(loc.getLatitude()))
                .append("\" lon=\"")
                .append(String.valueOf(loc.getLongitude()))
                .append("\">");

        track.append("<time>").append(dateTimeString).append("</time>");

        if (loc.hasBearing()) {
            track.append("<course>").append(String.valueOf(loc.getBearing())).append("</course>");
        }
        track.append("<src>").append(loc.getProvider()).append("</src>");

        if (satelliteCount > 0) {
            track.append("<sat>").append(String.valueOf(satelliteCount)).append("</sat>");
        }

        if (loc.getExtras() != null) {
            String hdop = loc.getExtras().getString("HDOP");
            String pdop = loc.getExtras().getString("PDOP");
            String vdop = loc.getExtras().getString("VDOP");
            String geoidheight = loc.getExtras().getString("GEOIDHEIGHT");
            String ageofdgpsdata = loc.getExtras().getString("AGEOFDGPSDATA");
            String dgpsid = loc.getExtras().getString("DGPSID");
            double bicyclingspeed = loc.getExtras().getDouble("bicyclingspeed",0);
            double bicyclingdistance = loc.getExtras().getDouble("bicyclingdistance",0);
            double bicyclingcadence = loc.getExtras().getDouble("bicyclingcadence",0);
            Boolean bicyclingoverwrite = loc.getExtras().getBoolean("bicyclingoverwrite",false);

            if (!Utilities.IsNullOrEmpty(hdop)) {
                track.append("<hdop>").append(hdop).append("</hdop>");
            }

            if (!Utilities.IsNullOrEmpty(vdop)) {
                track.append("<vdop>").append(vdop).append("</vdop>");
            }

            if (!Utilities.IsNullOrEmpty(pdop)) {
                track.append("<pdop>").append(pdop).append("</pdop>");
            }

            if (!Utilities.IsNullOrEmpty(geoidheight)) {
                track.append("<geoidheight>").append(geoidheight).append("</geoidheight>");
            }

            if (!Utilities.IsNullOrEmpty(ageofdgpsdata)) {
                track.append("<ageofdgpsdata>").append(ageofdgpsdata).append("</ageofdgpsdata>");
            }

            if (!Utilities.IsNullOrEmpty(dgpsid)) {
                track.append("<dgpsid>").append(dgpsid).append("</dgpsid>");
            }

            if (bicyclingdistance>0) {
                track.append("<!-- <extensions>");
                track.append("<bicycling:speed>").append(String.format("%.1f",bicyclingspeed)).append("</bicycling:speed>");
                track.append("<bicycling:distance>").append(String.format("%.3f",bicyclingdistance)).append("</bicycling:distance>");
                track.append("<bicycling:cadence>").append(String.format("%.0f",bicyclingcadence)).append("</bicycling:cadence>");
                if (bicyclingoverwrite) {
                    bicyclingoverwritten = true;
                    if (loc.hasSpeed()) {
                        track.append("<bicycling:gpsspeed>").append(String.valueOf(loc.getSpeed())).append("</bicycling:gpsspeed>");
                    }
                    if (loc.hasAltitude()) {
                        track.append("<bicycling:gpsele>").append(String.valueOf(loc.getAltitude())).append("</bicycling:gpsele>");
                    }
                }
                track.append("</extensions> -->");
                if (bicyclingoverwrite) {
                    track.append("<speed>").append(String.format("%.1f",bicyclingspeed)).append("</speed>");
                    track.append("<ele>").append(String.valueOf(bicyclingcadence)).append("</ele>");
                }

            }
        }

        if (loc.hasSpeed() && !bicyclingoverwritten) {
            track.append("<speed>").append(String.valueOf(loc.getSpeed())).append("</speed>");
        }

        if (loc.hasAltitude() && !bicyclingoverwritten) {
            track.append("<ele>").append(String.valueOf(loc.getAltitude())).append("</ele>");
        }

        track.append("</trkpt>\n");

        track.append("</trkseg></trk></gpx>");

        return track.toString();
    }

}


