package org.exoplatform.image;

import java.util.List;

public interface ImageRecognitionService {

    List<String> getLabelsOfImage(byte[] image) throws Exception;

}
