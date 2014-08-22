package de.is24.infrastructure.gridfs.http.web.controller;

import de.is24.infrastructure.gridfs.http.web.boot.AbstractContainerAndMongoDBStarter;
import org.apache.http.HttpResponse;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.junit.Before;
import org.junit.Test;

import static de.is24.infrastructure.gridfs.http.utils.RepositoryUtils.getHttpClientBuilder;
import static java.util.Arrays.asList;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.apache.http.util.EntityUtils.consume;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PropagationControllerIT extends AbstractContainerAndMongoDBStarter {

  private String rpmPropagationUrl;

  @Before
  public void setUp() throws Exception {
    rpmPropagationUrl = deploymentURL + "/propagation";
  }

  @Test
  public void return404ForRpmNotFoundWithOutContentType() throws Exception {
    givenCredentials("user", "user");
    HttpPost post = new HttpPost(rpmPropagationUrl);
    post.setEntity(new StringEntity("source=repo1/noarch/file.rpm&destination=repo2", (ContentType) null));
    HttpResponse response = httpClient.execute(post);
    consume(response.getEntity());

    assertThat(response.getStatusLine().getStatusCode(), is(SC_NOT_FOUND));
  }

  @Test
  public void return404ForRpmNotFound() throws Exception {
    givenCredentials("user", "user");
    HttpPost post = new HttpPost(rpmPropagationUrl);
    post.setEntity(new UrlEncodedFormEntity(asList(new BasicNameValuePair("source", "repo1/noarch/file.rpm"), new BasicNameValuePair("destination", "repo2"))));
    HttpResponse response = httpClient.execute(post);
    consume(response.getEntity());

    assertThat(response.getStatusLine().getStatusCode(), is(SC_NOT_FOUND));
  }

  private void givenCredentials(String user, String password) {
    httpClient = getHttpClientBuilder(user, password).build();
  }
}
