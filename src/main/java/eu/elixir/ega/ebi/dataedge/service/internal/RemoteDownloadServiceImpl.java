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
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import eu.elixir.ega.ebi.dataedge.config.GeneralStreamingException;
import eu.elixir.ega.ebi.dataedge.config.NotFoundException;
import eu.elixir.ega.ebi.dataedge.domain.entity.Transfer;
import eu.elixir.ega.ebi.dataedge.domain.repository.TransferRepository;
import eu.elixir.ega.ebi.dataedge.dto.DownloadEntry;
import eu.elixir.ega.ebi.dataedge.dto.EventEntry;
import eu.elixir.ega.ebi.dataedge.dto.File;
import eu.elixir.ega.ebi.dataedge.dto.FileDataset;
import eu.elixir.ega.ebi.dataedge.dto.HttpResult;
import eu.elixir.ega.ebi.dataedge.dto.RequestTicket;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import eu.elixir.ega.ebi.dataedge.service.DownloadService;
import eu.elixir.ega.ebi.dataedge.service.DownloaderLogService;
import java.math.BigInteger;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
public class RemoteDownloadServiceImpl implements DownloadService {

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
    @HystrixCommand
    public void downloadTicket(String ticket,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        // Get Ticket Details
        RequestTicket ticketObject = restTemplate.getForObject(SERVICE_URL + "/request/ticket/{ticket}/", RequestTicket.class, ticket);
        if (ticketObject==null) {
            throw new NotFoundException("Ticket not Found", ticket);
        }

        // No further verification - file is encrypted using previously specified key - only user knows it
        boolean success = download(ticketObject, response);

        // Finally - if the download was successful, delete the ticket!
        if (success) {
            restTemplate.delete(SERVICE_URL + "/request/{user_email}/ticket/{ticket}", ticketObject.getEmail(), ticket);
        }
    }
    
    @Override
    @HystrixCommand
    public void downloadFile(Authentication auth, 
                             String file_id, 
                             String key, 
                             String start, 
                             String end, 
                             HttpServletRequest request, 
                             HttpServletResponse response) {

        // Validate File Permissions - necessary because this is direct download!
        File f = getReqFile(file_id, auth);
        
        // Simulate a ticket
        if (f!=null) {
            String user_email = auth.getName();
            RequestTicket ticketObject = new RequestTicket(user_email,
                                                           "DIRECT",
                                                           "0",
                                                           f.getFileId(),
                                                           key,
                                                           "aes128",
                                                           "N/A",
                                                           "DIRECT",
                                                           new Timestamp(System.currentTimeMillis()),
                                                           Long.valueOf(start),
                                                           Long.valueOf(end));
            download(ticketObject, response);
        }
    }

    /*
     * Download Function
     */
    @HystrixCommand
    private boolean download(RequestTicket ticketObject,
                             HttpServletResponse response) {
        // Build Header - Specify UUID (Allow later stats query regarding this transfer)
        UUID dlIdentifier = UUID.randomUUID();
        String headerValue = dlIdentifier.toString();
        response = setHeaders(response, headerValue);

        // Variables needed for responses at the end of the function
        long timeDelta = 0;
        HttpResult xferResult = null;
        MessageDigest outDigest = null;
        
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
            
            // Build Request URI with Ticket Parameters and get requested file from RES (timed for statistics)
            timeDelta = System.currentTimeMillis();
            xferResult = restTemplate.execute(getResUri(ticketObject), HttpMethod.GET, requestCallback, responseExtractor);
            timeDelta = System.currentTimeMillis() - timeDelta;
            
        } catch (Throwable t) { // Log Error!
            EventEntry eev = getEventEntry(t, ticketObject);
            downloaderLogService.logEvent(eev);
            
            throw new GeneralStreamingException(t.toString(), 4);
        } finally {
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
            DownloadEntry dle = getDownloadEntry(success, speed, ticketObject);
            downloaderLogService.logDownload(dle);
        
            return success;
        }
    }
    
    /*
     * Helper Functions
     */
    @HystrixCommand
    private String getDigestText(byte[] inDigest) {
        BigInteger bigIntIn = new BigInteger(1,inDigest);
        String hashtext = bigIntIn.toString(16);
        while(hashtext.length() < 32 ){
            hashtext = "0"+hashtext;
        }                    
        return hashtext;
    }
    
    @HystrixCommand
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
    
    @HystrixCommand
    private URI getResUri(RequestTicket ticketObject) {
        String destFormat = ticketObject.getEncryptionType();
        destFormat = destFormat.equals("AES")?"aes128":destFormat; // default to 128-bit if not specified
        String url = RES_URL + "/file/archive/" + ticketObject.getFileId();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("destinationFormat", destFormat)
                .queryParam("destinationKey", ticketObject.getEncryptionKey())
                .queryParam("startCoordinate", ticketObject.getStartCoordinate())
                .queryParam("endCoordinate", ticketObject.getEndCoordinate());

        return builder.build().encode().toUri();
    }
    
    @HystrixCommand
    private Transfer getResSession(String resSession) {
        Transfer sessionResponse = restTemplate.getForObject(RES_URL + "/session/{ticket}/", Transfer.class, resSession);
        return sessionResponse;
    }
    
    @HystrixCommand
    private DownloadEntry getDownloadEntry(boolean success, double speed, RequestTicket ticketObject) {
        DownloadEntry dle = new DownloadEntry();
            dle.setDownloadLogId(0L);
            dle.setDownloadSpeed(speed);
            dle.setDownloadStatus(success?"success":"failed");
            dle.setFileId(ticketObject.getFileId());
            dle.setClientIp(ticketObject.getClientIp());
            dle.setEmail(ticketObject.getEmail());
            dle.setDownloadProtocol("http");
            dle.setServer("DATAEDGE");
            dle.setEncryptionType(ticketObject.getEncryptionType());
            dle.setCreated(new java.sql.Timestamp(Calendar.getInstance().getTime().getTime())); 

        return dle;
    }
    
    @HystrixCommand
    private EventEntry getEventEntry(Throwable t, RequestTicket ticketObject) {
        EventEntry eev = new EventEntry();
            eev.setEventId("0");
            eev.setClientIp(ticketObject.getClientIp());
            eev.setEvent(t.toString());
            eev.setDownloadTicket(ticketObject.getDownloadTicket());
            eev.setEventType("Error");
            eev.setEmail(ticketObject.getEmail());
            eev.setCreated(new java.sql.Timestamp(Calendar.getInstance().getTime().getTime())); 
        
        return eev;
    }

    @HystrixCommand
    @Cacheable(cacheNames="reqFile")
    private File getReqFile(String file_id, Authentication auth) {
        ResponseEntity<FileDataset[]> forEntityDataset = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}/datasets", FileDataset[].class, file_id);
        FileDataset[] bodyDataset = forEntityDataset.getBody();

        // Obtain all Authorised Datasets
        HashSet<String> permissions = new HashSet<>();
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
        while (iterator.hasNext()) {
            GrantedAuthority next = iterator.next();
            permissions.add(next.getAuthority());
        }
        
        // Is this File in at least one Authoised Dataset?
        ResponseEntity<File[]> forEntity = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}", File[].class, file_id);
        File[] body = forEntity.getBody();
        if (body!=null && bodyDataset!=null) {
            for (FileDataset f:bodyDataset) {
                String dataset_id = f.getDatasetId();
                if (permissions.contains(dataset_id) && body.length>=1) {
                    File ff = body[0];
                    ff.setDatasetId(dataset_id);
                    return ff;
                }
            }
        }
        
        return (new File());
    }
}
