/*
 * Copyright 2016 ELIXIR EBI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.elixir.ega.ebi.dataedge.service.internal;

import com.google.common.io.ByteStreams;
import eu.elixir.ega.ebi.dataedge.config.GeneralStreamingException;
import eu.elixir.ega.ebi.dataedge.config.VerifyMessage;
import eu.elixir.ega.ebi.dataedge.domain.entity.Transfer;
import eu.elixir.ega.ebi.dataedge.domain.repository.TransferRepository;
import eu.elixir.ega.ebi.dataedge.dto.DownloadEntry;
import eu.elixir.ega.ebi.dataedge.dto.EventEntry;
import eu.elixir.ega.ebi.dataedge.dto.File;
import eu.elixir.ega.ebi.dataedge.dto.HttpResult;
import eu.elixir.ega.ebi.dataedge.service.DownloaderLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import eu.elixir.ega.ebi.dataedge.service.FileService;
import java.math.BigInteger;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 *
 * @author asenf
 */
@Service
@Transactional
@EnableDiscoveryClient
public class RemoteFileServiceImpl implements FileService {

    //private final String SERVICE_URL = "http://DATA";
    private final String SERVICE_URL = "http://DOWNLOADER";
    private final String RES_URL = "http://RES";
    
    @Autowired
    RestTemplate restTemplate;

    // Database Repositories/Services
    
    @Autowired
    private TransferRepository transferRepository;
    
    @Autowired
    private DownloaderLogService downloaderLogService;
    
    @Override
    public void getFile(Authentication auth, 
                        String file_id,
                        String destinationFormat,
                        String destinationKey,
                        long startCoordinate,
                        long endCoordinate,
                        HttpServletRequest request,
                        HttpServletResponse response) {

        // Ascertain Access Permissions for specified File ID
        File reqFile = getReqFile(file_id, auth, request); // request added for ELIXIR

        // Build Header - Specify UUID (Allow later stats query regarding this transfer)
        UUID dlIdentifier = UUID.randomUUID();
        String headerValue = dlIdentifier.toString();
        response = setHeaders(response, headerValue);

        // Variables needed for responses at the end of the function
        long timeDelta = 0;
        HttpResult xferResult = null;
        MessageDigest outDigest = null;
        
        if (reqFile != null) {
            String user_email = auth.getName(); // For Logging

            try {
                // Get Send Stream - http Response, wrap in Digest Stream
                outDigest = MessageDigest.getInstance("MD5");
                DigestOutputStream outDigestStream = new DigestOutputStream(response.getOutputStream(), outDigest);

                // Get RES data stream, and copy it to output stream
                RequestCallback requestCallback = request_ -> request_.getHeaders()
                        .setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));

                // ----------------------------------------------------------------- Callback Function for Resttemplate
                // Get Data Stream from RES ReEncryptionService --------------------
                ResponseExtractor<HttpResult> responseExtractor = response_ -> {
                    List<String> get = response_.getHeaders().get("X-Session"); // RES session UUID
                    long b = 0;
                    String inHashtext = "";
                    try {
                        // Input stream from RES, wrap in DigestStream
                        MessageDigest inDigest = MessageDigest.getInstance("MD5");
                        DigestInputStream inDigestStream = new DigestInputStream(response_.getBody(), inDigest);
                        if (inDigestStream == null) {
                            throw new GeneralStreamingException("Unable to obtain Input Stream", 2);
                        }

                        // The actual Data Transfer - copy bytes from RES to Http connection to client
                        b = ByteStreams.copy(inDigestStream, outDigestStream); // in, outputStream

                        // Done - Close Streams and obtain MD5 of received Stream
                        inDigestStream.close();
                        outDigestStream.close();
                        inHashtext = getDigestText(inDigest.digest());
                    } catch (Throwable t) {
                        inHashtext = t.getMessage();
                    }

                    // return number of bytes copied, RES session header, and MD5 of RES input stream
                    return new HttpResult(b, get, inHashtext); // This is the result of the RestTemplate
                };
                // -----------------------------------------------------------------

                /*
                 * CUSTOMISATION: If you access files by absolute path (nearly everyone)
                 * then gall getResUri with the file path instead of the file ID
                 * [...]getResUri(reqFile.getFileName(),destinationFormat[...]
                 */
                
                // Build Request URI with Ticket Parameters and get requested file from RES (timed for statistics)
                timeDelta = System.currentTimeMillis();
                xferResult = restTemplate.execute(getResUri(file_id,destinationFormat,destinationKey,startCoordinate,endCoordinate), HttpMethod.GET, requestCallback, responseExtractor);
                timeDelta = System.currentTimeMillis() - timeDelta;

            } catch (Throwable t) { // Log Error!
                EventEntry eev = getEventEntry(t, "TODO ClientIp", "Direct Download", user_email);
                downloaderLogService.logEvent(eev);

                throw new GeneralStreamingException(t.toString(), 4);
            } finally {
                if (xferResult != null) {
                    Transfer received = getResSession(xferResult.getSession().get(0)); // Shortcut -- Same Database; otherwise perform a REST call to RES
                    System.out.println("Received? " + (received==null?"null":received.toString()));

                    // Compare received MD5 with RES
                    String inHashtext = xferResult.getMd5();
                    String outHashtext = getDigestText(outDigest.digest());

                    // Store with UUID for later retrieval - in case of error or success            
                    Transfer transfer = new Transfer(headerValue,
                                                     new java.sql.Timestamp(Calendar.getInstance().getTime().getTime()),
                                                     inHashtext,
                                                     outHashtext,
                                                     0,
                                                     xferResult.getBytes(),
                                                     "DATAEDGE");
                    Transfer save = transferRepository.save(transfer);

                    // Compare - Sent MD5 equals Received MD5? - Log Download in DB
                    boolean success = outHashtext.equals(inHashtext);
                    double speed = (xferResult.getBytes()/1024.0/1024.0)/(timeDelta*1000.0);
                    System.out.println("Success? " + success + ", Speed: " + speed + " MB/s");
                    DownloadEntry dle = getDownloadEntry(success, speed, file_id, "TODO CLientIp", user_email, destinationFormat);
                    downloaderLogService.logDownload(dle);                
                }
            }
        }
    }

    @Override
    public Object getFileHeader(Authentication auth, String file_id, String destinationFormat, String destinationKey) {
        Object header = null;
        
        // Ascertain Access Permissions for specified File ID
        File reqFile = getReqFile(file_id, auth, null);
        if (reqFile!=null) {
            header = restTemplate.getForObject(RES_URL + "/ga4gh/{fileId}/header", Object.class, file_id);
        }
        
        return header;
    }
    
    /*
     * Helper Functions
     */
    private String getDigestText(byte[] inDigest) {
        BigInteger bigIntIn = new BigInteger(1,inDigest);
        String hashtext = bigIntIn.toString(16);
        while(hashtext.length() < 32 ){
            hashtext = "0"+hashtext;
        }                    
        return hashtext;
    }
    
    private HttpServletResponse setHeaders(HttpServletResponse response, String headerValue) {
        // Set headers for the response
        String headerKey = "X-Session";
        response.setHeader(headerKey, headerValue);

        // get MIME type of the file (actually, it's always this for now)
        String mimeType = "application/octet-stream";
        System.out.println("MIME type: " + mimeType);

        // set content attributes for the response
        response.setContentType(mimeType);        
        
        return response;
    }
    
    private URI getResUri(String fileStableIdPath,
                          String destFormat,
                          String destKey,
                          Long startCoord,
                          Long endCoord) {
        destFormat = destFormat.equals("AES")?"aes128":destFormat; // default to 128-bit if not specified
        String url = RES_URL + "/file";
        if (fileStableIdPath.startsWith("EGAF")) { // If an ID is specified - resolve this in RES
            url += "/archive/" + fileStableIdPath;
        }
        
        // Build components based on Parameters provided
        UriComponentsBuilder builder = null;
        
        if (startCoord==0 && endCoord==0 && destFormat.equalsIgnoreCase("plain")) {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        } else if (startCoord==0 && endCoord==0) {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("destinationKey", destKey)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        } else if (destFormat.equalsIgnoreCase("plain")) {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("startCoordinate", startCoord)
                    .queryParam("endCoordinate", endCoord)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("destinationKey", destKey)
                    .queryParam("startCoordinate", startCoord)
                    .queryParam("endCoordinate", endCoord)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        }

        return builder.build().encode().toUri();
    }
    
    private Transfer getResSession(String resSession) {
        Transfer sessionResponse = null;
        try {
            sessionResponse = restTemplate.getForObject(RES_URL + "/session/{ticket}/", Transfer.class, resSession);
        } catch (HttpClientErrorException ex) {
            sessionResponse = new Transfer();
        }
        return sessionResponse;
    }
    
    private DownloadEntry getDownloadEntry(boolean success, double speed, String fileStableId,
                                                                          String clientIp,
                                                                          String user_email,
                                                                          String encryptionType) {
        DownloadEntry dle = new DownloadEntry();
            dle.setDownloadLogId(0L);
            dle.setDownloadSpeed(speed);
            dle.setDownloadStatus(success?"success":"failed");
            dle.setFileStableId(fileStableId);
            dle.setClientIp(clientIp);
            dle.setUserEmail(user_email);
            dle.setDownloadProtocol("http");
            dle.setServer("DATAEDGE");
            dle.setEncryptionType(encryptionType);
            dle.setCreated(new java.sql.Timestamp(Calendar.getInstance().getTime().getTime())); 

        return dle;
    }
    
    private EventEntry getEventEntry(Throwable t, String clientIp,
                                                  String ticket,
                                                  String user_email) {
        EventEntry eev = new EventEntry();
            eev.setEventId("0");
            eev.setClientIp(clientIp);
            eev.setEvent(t.toString());
            eev.setDownloadTicket(ticket);
            eev.setEventType("Error");
            eev.setUserEmail(user_email);
            eev.setCreated(new java.sql.Timestamp(Calendar.getInstance().getTime().getTime())); 
        
        return eev;
    }
    
    private File getReqFile(String file_id, Authentication auth, HttpServletRequest request) {

        // Obtain all Authorised Datasets (Provided by EGA AAI)
        HashSet<String> permissions = new HashSet<>();
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities != null && authorities.size() > 0) {
            Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
            while (iterator.hasNext()) {
                GrantedAuthority next = iterator.next();
                permissions.add(next.getAuthority());
            }
        } else if (request!=null) { // ELIXIR User Case: Obtain Permmissions from X-Permissions Header
            //String permissions_ = request.getHeader("X-Permissions");
            try {
                List<String> permissions_ = (new VerifyMessage(request.getHeader("X-Permissions"))).getPermissions();
                if (permissions_ != null && permissions_.size() > 0) {
                    //StringTokenizer t = new StringTokenizer(permissions_, ",");
                    //while (t!=null && t.hasMoreTokens()) {
                    for (String ds:permissions) {
                        //String ds = t.nextToken();
                        if (ds != null) {
                            permissions.add(ds);
                        }
                    }
                }            
            } catch (Exception ex) {}
        }
        
        File reqFile = null;
        ResponseEntity<File[]> forEntity = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}", File[].class, file_id);
        File[] body = forEntity.getBody();
        if (body!=null) {
            for (File f:body) {
                String dataset_id = f.getDatasetStableId();
                if (permissions.contains(dataset_id)) {
                    reqFile = f;
                    break;
                }
            }
        }
        return reqFile;
    }
}
