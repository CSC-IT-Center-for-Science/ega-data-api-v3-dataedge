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
package eu.elixir.ega.ebi.dataedge.dto;

import java.io.Serializable;
import java.sql.Timestamp;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 *
 * @author asenf
 */
@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class DownloadEntry implements Serializable {
    
    private Long downloadLogId;
    private String clientIp;
    private String server;
    private String userEmail;
    private String fileStableId;
    private double downloadSpeed;
    private String downloadStatus;
    private String downloadProtocol;
    private String encryptionType;
    private Timestamp created;
    
    @Override
    public String toString() {
            return "DownloadEntry [downloadLogId=" + downloadLogId + 
                   ", clientIp=" + clientIp +
                   ", server=" + server +
                   ", userEmail=" + userEmail +
                   ", fileStableId=" + fileStableId +
                   ", downloadSpeed=" + downloadSpeed +
                   ", downloadStatus=" + downloadStatus +
                   ", downloadProtocol=" + downloadProtocol +
                   ", encryptionType=" + encryptionType +
                   ", created=" + created + "]";
    }    
}
