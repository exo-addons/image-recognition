package org.exoplatform.image;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.chain.Context;
import org.apache.commons.io.IOUtils;

import org.exoplatform.commons.search.index.IndexingService;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.services.document.DCMetaData;
import org.exoplatform.services.ext.action.InvocationContext;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.impl.Constants;
import org.exoplatform.services.jcr.impl.core.NodeImpl;
import org.exoplatform.services.jcr.impl.core.PropertyImpl;
import org.exoplatform.services.jcr.impl.ext.action.AdvancedAction;
import org.exoplatform.services.jcr.impl.ext.action.AdvancedActionException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.wcm.core.NodetypeConstant;
import org.exoplatform.services.wcm.search.connector.FileindexingConnector;

/**
 * JCR action which sends image files to Google Vision API to get labels and add them in the file node metadata
 */
public class ImageRecognitionAction implements AdvancedAction {
  private static final Log LOGGER = ExoLogger.getExoLogger(ImageRecognitionAction.class);

  private ImageRecognitionService imageRecognitionService;

  private SessionProviderService sessionProviderService;

  private RepositoryService repositoryService;

  private IndexingService indexingService;

  public ImageRecognitionAction() {
    this.imageRecognitionService = CommonsUtils.getService(GoogleVisionImageRecognitionService.class);
    this.sessionProviderService = CommonsUtils.getService(SessionProviderService.class);
    this.repositoryService = CommonsUtils.getService(RepositoryService.class);
    this.indexingService = CommonsUtils.getService(IndexingService.class);
  }

  @Override
  public boolean execute(Context context) throws Exception {
    PropertyImpl property = (PropertyImpl) context.get(InvocationContext.CURRENT_ITEM);
    NodeImpl parent = property.getParent();

    if (!parent.isNodeType("nt:resource") || !property.getInternalName().equals(Constants.JCR_DATA)) {
      return true;
    }

    processNode(parent);

    return true;
  }

  protected void processNode(Node node) throws RepositoryException {
    if (node.isNodeType(NodetypeConstant.NT_RESOURCE)) {
      node = node.getParent();
    }
    if (node.isNodeType(NodetypeConstant.NT_FILE)) {
      String workspaceName = node.getSession().getWorkspace().getName();
      String fileNodePath = node.getPath();

      ExoContainer container = ExoContainerContext.getCurrentContainer();

      CompletableFuture.runAsync(() -> {
        try {
          ExoContainerContext.setCurrentContainer(container);

          SessionProvider systemSessionProvider = sessionProviderService.getSystemSessionProvider(null);
          Session session = systemSessionProvider.getSession(workspaceName, repositoryService.getCurrentRepository());

          String fileNodeRelativePath = fileNodePath.substring(1);
          // dirty ? I prefer the term pragmatic
          do {
            Thread.sleep(500);
          } while (!session.getRootNode().hasNode(fileNodeRelativePath));

          Node fileNode = session.getRootNode().getNode(fileNodeRelativePath);

          List<String> labels = getLabels(fileNode);

          // update description
          addLabelsToNode(fileNode, labels);

          RequestLifeCycle.begin(container);
          // Force reindexing
          indexingService.index(FileindexingConnector.TYPE, ((NodeImpl)fileNode).getInternalIdentifier());
        } catch (Exception e) {
          LOGGER.error("Error while adding labels to file node : " + e.getMessage(), e);
        } finally {
          RequestLifeCycle.end();
        }
      });
    }
  }

  /**
   * Get the labels of the given node
   * @param node The node holding the image file
   * @return The labels of the image returned by Google Vision API
   */
  protected List<String> getLabels(Node node) {
    List<String> labels = new ArrayList<>();

    try {
      if (node.hasNode(NodetypeConstant.JCR_CONTENT)) {
        Node contentNode = node.getNode(NodetypeConstant.JCR_CONTENT);
        if (contentNode != null) {
          String fileName = node.getName().toLowerCase();

          if (!fileName.endsWith(".jpg") && !fileName.endsWith(".jpeg") && !fileName.endsWith(".png")) {
          //if (!contentNode.hasProperty(NodetypeConstant.JCR_MIMETYPE) ||
          //    !contentNode.getProperty(NodetypeConstant.JCR_MIMETYPE).getString().startsWith("image/")) {
            LOGGER.info("Image recognition - Not an image");
            return labels;
          }

          // make sure we can update the description
          if (!contentNode.isNodeType("dc:elementSet") && contentNode.canAddMixin("dc:elementSet")) {
            contentNode.addMixin("dc:elementSet");
          }

          InputStream fileStream = contentNode.getProperty(NodetypeConstant.JCR_DATA).getStream();
          byte[] fileBytes = IOUtils.toByteArray(fileStream);

          // Performs label detection on the image file
          LOGGER.info("Image recognition - calling Google Vision API for image " + node.getPath());
          labels = imageRecognitionService.getLabelsOfImage(fileBytes);
        }
      }
    } catch (Exception e) {
      LOGGER.error("Error while image recognition", e);
    }

    return labels;
  }

  /**
   * Add the given labels to the node, as concatenated string in the description metadata
   * @param node The node of the image file
   * @param labels The labels to add
   * @throws Exception
   */
  private void addLabelsToNode(Node node, List<String> labels) throws Exception {
    if(labels != null && labels.size() > 0) {
      Properties properties = new Properties();
      properties.put(DCMetaData.DESCRIPTION, String.join(" ", labels));
      node.getNode(NodetypeConstant.JCR_CONTENT).setProperty(NodetypeConstant.DC_DESCRIPTION, new String[] {String.join(" ", labels)});
      node.getSession().save();
    }
  }

  @Override
  public void onError(Exception e, Context context) throws AdvancedActionException {
    LOGGER.error("Error while enriching file metadata", e);
  }
}
