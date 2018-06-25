package org.exoplatform.image;

import java.util.ArrayList;
import java.util.List;

import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;

import org.apache.commons.lang.StringUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Image recognition service which allows to fetch labels of an image thanks to
 * Google Vision API
 */
public class GoogleVisionImageRecognitionService implements ImageRecognitionService {

  private static final Log LOGGER                     = ExoLogger.getExoLogger(ImageRecognitionAction.class);

  private String           LABEL_THRESHOLD_PARAM_NAME = "labelThreshold";

  private float            labelThreshold             = 0.75f;

  public GoogleVisionImageRecognitionService(InitParams initParams) {
    if (initParams != null) {
      ValueParam labelThresholdValueParam = initParams.getValueParam(LABEL_THRESHOLD_PARAM_NAME);
      if (labelThresholdValueParam != null && StringUtils.isNotEmpty(labelThresholdValueParam.getValue())) {
        Float labelThresholdTmp = null;
        try {
          labelThresholdTmp = Float.valueOf(labelThresholdValueParam.getValue());
          if (labelThresholdTmp < 0 || labelThresholdTmp > 1) {
            LOGGER.error("Image recognition label threshold (" + labelThresholdTmp
                + ") is not valid (must be a float between 0 and 1). Using default value " + labelThreshold + ".");
          } else {
            labelThreshold = labelThresholdTmp;
          }
        } catch (NumberFormatException e) {
          LOGGER.error("Image recognition label threshold (" + labelThresholdTmp
              + ") is not valid (must be a float between 0 and 1). Using default value " + labelThreshold + ".");
        }
      }
    }
  }

  public List<String> getLabelsOfImage(byte[] image) throws Exception {
    List<String> labels = new ArrayList<>();

    // Instantiates a client
    try (ImageAnnotatorClient vision = ImageAnnotatorClient.create()) {

      // Reads the image file into memory
      ByteString imgBytes = ByteString.copyFrom(image);

      // Builds the image annotation request
      List<AnnotateImageRequest> requests = new ArrayList<>();
      Image img = Image.newBuilder().setContent(imgBytes).build();
      Feature feat = Feature.newBuilder().setType(Feature.Type.LABEL_DETECTION).build();
      AnnotateImageRequest request = AnnotateImageRequest.newBuilder().addFeatures(feat).setImage(img).build();
      requests.add(request);

      // Performs label detection on the image file
      BatchAnnotateImagesResponse response = vision.batchAnnotateImages(requests);
      List<AnnotateImageResponse> responses = response.getResponsesList();

      for (AnnotateImageResponse res : responses) {
        if (res.hasError()) {
          LOGGER.error("Error: {}\n", res.getError().getMessage());
          break;
        }

        for (EntityAnnotation annotation : res.getLabelAnnotationsList()) {
          if (annotation.getScore() > labelThreshold) {
            LOGGER.info("Image recognition - found label '{}' (score={})", annotation.getDescription(), annotation.getScore());
            labels.add(annotation.getDescription());
          }
        }
      }
    }

    return labels;
  }
}
