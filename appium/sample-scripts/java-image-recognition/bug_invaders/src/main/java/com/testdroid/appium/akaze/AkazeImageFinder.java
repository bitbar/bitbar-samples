package com.testdroid.appium.akaze;
/**
 * Created by rucindrea on 12/2/14.
 */
import org.apache.commons.io.IOUtils;
import org.opencv.calib3d.*;
import org.opencv.core.*;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Scalar;
import org.opencv.core.Point;
import org.opencv.highgui.Highgui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import org.json.*;

public class AkazeImageFinder {
    private static final Logger logger = LoggerFactory.getLogger(AkazeImageFinder.class);
    public String rotation;
    
    public static int objMatchPoints = 0;
    public static int sceneMatchPoints = 0;

    public AkazeImageFinder() {
        rotation = "notSet";
    }

    public AkazeImageFinder(String setRotation) {
        rotation = setRotation;
    }

    public Point[] findImage(String object_filename_nopng, String scene_filename_nopng) {

        boolean calculateRotation = false;
        logger.info("AkazeImageFinder - findImage() started...");
        setupOpenCVEnv();
        String object_filename = object_filename_nopng + ".png";
        String scene_filename = scene_filename_nopng + ".png";

        Mat img_object = Highgui.imread(object_filename, Highgui.CV_LOAD_IMAGE_UNCHANGED);
        Mat img_scene = Highgui.imread(scene_filename, Highgui.CV_LOAD_IMAGE_UNCHANGED);
        rotateImage(scene_filename, img_scene);
        String jsonResults = null;
        try {
            jsonResults = runAkazeMatch(object_filename, scene_filename);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        if (jsonResults == null) {
            return null;
        }

        logger.info("Keypoints for {} to be found in {} are in file {}", object_filename, scene_filename, jsonResults);

        double initial_height = img_object.size().height;
        double initial_width = img_object.size().width;

        Highgui.imwrite(scene_filename, img_scene);

        //finding homography
        LinkedList<Point> objList = new LinkedList<Point>();
        LinkedList<Point> sceneList = new LinkedList<Point>();
        JSONObject jsonObject = getJsonObject(jsonResults);
        if (jsonObject == null) {
            logger.error("ERROR: Json file couldn't be processed. ");
            return null;
        }
        JSONArray keypointsPairs = null;
        try {
            keypointsPairs = jsonObject.getJSONArray("keypoint-pairs");
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
        Point[] objPoints = new Point[keypointsPairs.length()];
        Point[] scenePoints = new Point[keypointsPairs.length()];
        int j = 0;
        for (int i = 0; i < keypointsPairs.length(); i++) {
            try {
                objPoints[j] = new Point(Integer.parseInt(keypointsPairs.getJSONObject(i).getString("x1")), Integer.parseInt(keypointsPairs.getJSONObject(i).getString("y1")));
                scenePoints[j] = new Point(Integer.parseInt(keypointsPairs.getJSONObject(i).getString("x2")), Integer.parseInt(keypointsPairs.getJSONObject(i).getString("y2")));
                j++;
            } catch (JSONException e) {
                e.printStackTrace();
                return null;
            }

        }

        String filename = scene_filename_nopng + "_with_results.png";
        Mat pointsImg = Highgui.imread(scene_filename, Highgui.CV_LOAD_IMAGE_COLOR);

        Mat objectImg = Highgui.imread(object_filename, Highgui.CV_LOAD_IMAGE_COLOR);
        for (int i = 0; i < objPoints.length; i++) {
            Point objectPoint = new Point(objPoints[i].x, objPoints[i].y);
            objList.addLast(objectPoint);
            Core.circle(objectImg, objectPoint, 5, new Scalar(0, 255, 0, -5));
            Point scenePoint = new Point(scenePoints[i].x - initial_width, scenePoints[i].y);
            sceneList.addLast(scenePoint);
            Core.circle(pointsImg, scenePoint, 5, new Scalar(0, 255, 0, -5));
        }
        Highgui.imwrite(filename, pointsImg);

        objMatchPoints = objList.size();
        sceneMatchPoints = sceneList.size();
        
        if ((objList.size() < 4) || (sceneList.size() < 4)) {
            logger.error("Not enough mathches found. ");
            return null;
        }

        MatOfPoint2f obj = new MatOfPoint2f();
        obj.fromList(objList);
        MatOfPoint2f scene = new MatOfPoint2f();
        scene.fromList(sceneList);

        Mat H = Calib3d.findHomography(obj, scene);

        Mat scene_corners = drawFoundHomography(scene_filename_nopng, img_object, filename, H);

        Point top_left = new Point(scene_corners.get(0, 0));
        Point top_right = new Point(scene_corners.get(1, 0));
        Point bottom_left = new Point(scene_corners.get(3, 0));
        Point bottom_right = new Point(scene_corners.get(2, 0));

        calculateRotation = calculateImageRotation(calculateRotation, top_left, top_right, bottom_left, bottom_right);
        Point center = new Point(top_left.x + (top_right.x - top_left.x) / 2, top_left.y + (bottom_left.y - top_left.y) / 2);
        logger.info("Image found at coordinates: " + (int) center.x + ", " + (int) center.y);

        Point[] points = new Point[6];
        points[0] = top_left;
        points[1] = top_right;
        points[2] = bottom_left;
        points[3] = bottom_right;
        points[4] = center;


        double initial_ratio = initial_height / initial_width;
        double found_ratio1 = (bottom_left.y - top_left.y) / (top_right.x - top_left.x);
        double found_ratio2 = (bottom_right.y - top_right.y) / (bottom_right.x - bottom_left.x);
        if (checkFoundImageDimensions(calculateRotation, top_left, top_right, bottom_left, bottom_right))
            return null;
        if (checkFoundImageSizeRatio(calculateRotation, initial_height, initial_width, top_left, top_right, bottom_left, bottom_right, initial_ratio, found_ratio1, found_ratio2))
            return null;

        return points;
    }


    private Mat drawFoundHomography(String scene_filename_nopng, Mat img_object, String filename, Mat h) {
        Mat obj_corners = new Mat(4, 1, CvType.CV_32FC2);
        Mat scene_corners = new Mat(4, 1, CvType.CV_32FC2);

        obj_corners.put(0, 0, new double[]{0, 0});
        obj_corners.put(1, 0, new double[]{img_object.cols(), 0});
        obj_corners.put(2, 0, new double[]{img_object.cols(), img_object.rows()});
        obj_corners.put(3, 0, new double[]{0, img_object.rows()});

        Core.perspectiveTransform(obj_corners, scene_corners, h);

        Mat img = Highgui.imread(filename, Highgui.CV_LOAD_IMAGE_COLOR);

        Core.line(img, new Point(scene_corners.get(0, 0)), new Point(scene_corners.get(1, 0)), new Scalar(0, 255, 0), 4);
        Core.line(img, new Point(scene_corners.get(1, 0)), new Point(scene_corners.get(2, 0)), new Scalar(0, 255, 0), 4);
        Core.line(img, new Point(scene_corners.get(2, 0)), new Point(scene_corners.get(3, 0)), new Scalar(0, 255, 0), 4);
        Core.line(img, new Point(scene_corners.get(3, 0)), new Point(scene_corners.get(0, 0)), new Scalar(0, 255, 0), 4);


        filename = scene_filename_nopng + "_with_results.png";
        Highgui.imwrite(filename, img);
        return scene_corners;
    }

    private boolean checkFoundImageSizeRatio(boolean calculateRotation, double initial_height, double initial_width, Point top_left, Point top_right, Point bottom_left, Point bottom_right, double initial_ratio, double found_ratio1, double found_ratio2) {
        //check the image size, if too small incorrect image was found - only if rotation has been set, otherwise points will be incorrect

        if (calculateRotation == false) {
            if ((round(found_ratio1 / initial_ratio, 2) > 1.9) || (round(initial_ratio / found_ratio2, 2) > 1.9)
                    || (round(found_ratio1 / initial_ratio, 2) < 0.5) || (round(initial_ratio / found_ratio2, 2) < 0.5)) {
                logger.error("Size of image found is incorrect, check the ratios for more info:");
                logger.info("Initial height of query image: " + initial_height);
                logger.info("Initial width of query image: " + initial_width);
                logger.info("Initial ratio for query image: " + initial_height / initial_width);

                logger.info("Found top width: " + (top_right.x - top_left.x));
                logger.info("Found bottom width: " + (bottom_right.x - bottom_left.x));

                logger.info("Found left height: " + (bottom_left.y - top_left.y));
                logger.info("Found right height: " + (bottom_right.y - top_right.y));
                logger.info("Found ratio differences: " + round(found_ratio1 / initial_ratio, 1) + " and " + round(initial_ratio / found_ratio2, 1));
                return true;
            }
        }
        return false;
    }

    private boolean checkFoundImageDimensions(boolean calculateRotation, Point top_left, Point top_right, Point bottom_left, Point bottom_right) {
        //check any big differences in hight and width on each side
        if (calculateRotation == false) {
            double left_height = bottom_left.y - top_left.y;
            double right_height = bottom_right.y - top_right.y;
            double height_ratio = round(left_height / right_height, 2);

            double top_width = top_right.x - top_left.x;
            double bottom_width = bottom_right.x - bottom_left.x;
            double width_ratio = round(top_width / bottom_width, 2);

            logger.info("Height and width ratios: " + height_ratio + " and " + width_ratio);

            if ((height_ratio < 0.5) || (height_ratio > 1.9) || (width_ratio < 0.5) || (width_ratio > 1.9)) {
                logger.info("Height and width ratios: " + height_ratio + " and " + width_ratio);
                logger.error("Image found is not the correct shape, height or width are different on each side.");
                return true;
            }
        }
        return false;
    }

    private boolean calculateImageRotation(boolean calculateRotation, Point top_left, Point top_right, Point bottom_left, Point bottom_right) {
        if (rotation.equals("notSet")) {
            calculateRotation = true;
            //we need to calculate rotation first, if this is not set
            //process the coordinates found - transform negative values to 0, get correct sizes
            //Point[] object_points = new Point[] {top_left,top_right,bottom_left,bottom_right};
            Point[] scene_points = new Point[]{top_left, top_right, bottom_left, bottom_right};
            Arrays.sort(scene_points, new PointSortY());
            //keep only biggest y out of the 2 small values:
            scene_points[0].y = scene_points[1].y;
            //keep only the smallest value out of the 2 high values:
            scene_points[3].y = scene_points[2].y;

            Arrays.sort(scene_points, new PointSortX());
            //keep only highest x out of the 2 small values:
            scene_points[0].x = scene_points[1].x;
            //keep only the smallest value out of the 2 high values:
            scene_points[3].x = scene_points[2].x;

            Point[] left_points = new Point[]{scene_points[0], scene_points[1]};
            Arrays.sort(left_points, new PointSortY());
            Point[] right_points = new Point[]{scene_points[2], scene_points[3]};
            Arrays.sort(right_points, new PointSortY());

            Point scene_top_left = new Point(left_points[0].x, left_points[0].y);

            if (scene_top_left.equals(top_left)) {
                rotation = "0 degrees";
                logger.info("No rotation needed, object found in the same position in scene.");
            } else if (scene_top_left.equals(bottom_left)) {
                rotation = "90 degrees";
                logger.info("Scene is rotated 90 degrees to the right. ");
            } else if (scene_top_left.equals(bottom_right)) {
                rotation = "180 degrees";
                logger.info("Scene is rotated 180 degrees to the right. ");

            } else if (scene_top_left.equals(top_right)) {
                rotation = "270 degrees";
                logger.info("Scene is rotated 270 degrees to the right. ");
            }

            logger.info("Rotation is: " + rotation);
        }
        return calculateRotation;
    }

    private String runAkazeMatch(String object_filename, String scene_filename) throws InterruptedException, IOException {

        long timestamp = System.currentTimeMillis();
        String jsonFilename = "./target/keypoints/keypoints_" + timestamp + ".json";
        logger.info("Json file should be found at: {}", jsonFilename);
        File file = new File(jsonFilename);
        file.getParentFile().mkdirs();
        String[] akazeMatchCommand = {"akaze/bin/akaze_match", object_filename, scene_filename, "--json", jsonFilename, "--dthreshold", "0.00000000001"};
        try
        {
            ProcessBuilder p = new ProcessBuilder(akazeMatchCommand);
            Process proc = p.start();
            InputStream stdin = proc.getInputStream();
            InputStreamReader isr = new InputStreamReader(stdin);
            BufferedReader br = new BufferedReader(isr);
            String line = null;
            while ( (line = br.readLine()) != null)
                System.out.print(".");
            int exitVal = proc.waitFor();
            logger.info("Akaze matching process exited with value: " + exitVal);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        if (!file.exists()) {
            logger.error("ERROR: Image recognition with Akaze faield. No json file created.");
            return null;
        }
        else {
            return jsonFilename;
        }
    }

    private void rotateImage(String scene_filename, Mat img_scene) {
        if (rotation.equals("90 degrees")) {
            rotateImage90n(img_scene, img_scene, 90);
        }
        if (rotation.equals("180 degrees")) {
            rotateImage90n(img_scene, img_scene, 180);
        }
        if (rotation.equals("270 degrees")) {
            rotateImage90n(img_scene, img_scene, 270);
        }

        Highgui.imwrite(scene_filename, img_scene);
    }

    private void setupOpenCVEnv() {
    	System.out.println(System.getProperty("java.library.path"));
    	System.setProperty("java.library.path", "opencv-2.4.9");
    	
        Field fieldSysPath = null;
        try {
            fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        fieldSysPath.setAccessible(true);
        try {
            fieldSysPath.set(null, null);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private JSONObject getJsonObject(String filename) {
        File jsonFile = new File(filename);
        InputStream is = null;
        try {
            is = new FileInputStream(jsonFile);
            String jsonTxt = IOUtils.toString(is);
            return new JSONObject(jsonTxt);

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }

    }


    public double getComponents(Mat h) {
        double a = h.get(0, 0)[0];
        double b = h.get(0, 1)[0];
        double theta = Math.atan2(b, a);
        return theta;
//
    }


    public void rotateImage90n(Mat source, Mat dest, int angle) {
        // angle : factor of 90, even it is not factor of 90, the angle will be mapped to the range of [-360, 360].
        // {angle = 90n; n = {-4, -3, -2, -1, 0, 1, 2, 3, 4} }
        // if angle bigger than 360 or smaller than -360, the angle will be mapped to -360 ~ 360
        // mapping rule is : angle = ((angle / 90) % 4) * 90;
        //
        // ex : 89 will map to 0, 98 to 90, 179 to 90, 270 to 3, 360 to 0.

        source.copyTo(dest);

        angle = ((angle / 90) % 4) * 90;

        int flipHorizontalOrVertical;
        //0 : flip vertical; 1 flip horizontal
        if (angle > 0) {
            flipHorizontalOrVertical = 0;
        } else {
            flipHorizontalOrVertical = 1;
        }

        int number = (int) (angle / 90);

        for (int i = 0; i != number; ++i) {
            Core.transpose(dest, dest);
            Core.flip(dest, dest, flipHorizontalOrVertical);
        }
    }

    public String getRotation() {
        return rotation;
    }

    public static void main(String[] args) throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException, JSONException {
        String object;

        try {
            object = args[0];
        } catch (Exception e) {
            object = "ok_button";
        }
        System.out.println(System.getProperty("user.dir"));


        int min_multiplier_tolerance = 8;
        System.out.println();
        System.out.println("Checking image: " + object);
        String scene = "./target/reports/SamsungTab/screenshots/hayday/05_" + object + "_screenshot";
        //String scene = "./target/04_progress_ok_cancel_screenshot";
        String object_filename = "./queryImages/hayday/" + object;
        //String object_filename = "/Users/rucindrea/workspace/GameTest/gametest/gameImages/queryimages/" + object;
        String scene_filename = scene;
        System.out.println(object_filename);
        System.out.println(scene_filename);
        AkazeImageFinder finder = new AkazeImageFinder();
        finder.rotation = "notSet";
        finder.findImage(object_filename, scene_filename);
    }

    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
}


class PointSortX implements Comparator<Point> {

    public int compare(Point a, Point b) {
        return (a.x < b.x) ? -1 : (a.x > b.x) ? 1 : 0;
    }
}


class PointSortY implements Comparator<Point> {

    public int compare(Point a, Point b) {
        return (a.y < b.y) ? -1 : (a.y > b.y) ? 1 : 0;
    }
}
