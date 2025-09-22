'use strict';

const WEB_SOCKET_URL = document.URL.replace(/http(.*)\/[^\/]*$/, 'WebSocketServlet');

/** manager of websocket-connection */
var wsManager = new WebSocketSessionManager(WEB_SOCKET_URL, handleMessage);

/**
 * Returns an element by selector.
 * 
 * @param selector
 *            selector
 * @returns HTML-element
 */
function $(selector) {
    if (selector) {
        let el = document.querySelector(selector);
        if (!el) {
            throw `No elements matching selector (${selector}).`;
        }
        return el;
    }
}

function addMessage(msg) {
    var elMessages = $('#messages');
    elMessages.value =`* ${msg}\n${elMessages.value}`;
}

/**
 * Manager of the web-socket-session.
 *  
 * @param webSocketUrl URL of the web-socket
 * @param handleMessageFunction function to handle incoming messages
 */
function WebSocketSessionManager(webSocketUrl, handleMessageFunction) {
    console.log('WebSocketSessionManager: URL=%s', webSocketUrl);

    this.tsConnectionInit = null;
    this.connection = null;

    this.close = function() {
        if (this.connection && this.connection.readyState == WebSocket.OPEN) {
            addMessage("The WS-connection will be closed.");
            this.connection.close();
        }
    }

    this.handleOpen = function(onOpenFunction) {
        console.log('WebSocket: open');

        this.connection.onmessage = handleMessageFunction;
        if (onOpenFunction) {
            onOpenFunction();
        }
    };
    this.handleError = function(event) {
        addMessage(`Can't open web-socket connection: ${event}`);
        console.log(`WebSocket: error ${event}`);
    };

    this.handleClose = function() {
        console.log('WebSocketConnection %s closed', this.connection);
    };

    this.initWebSocket = function(onOpenFunction) {
        if (this.connection) {
            this.connection.close();
        }

        this.tsConnectionInit = new Date();
        this.connection = new WebSocket(webSocketUrl);
        this.connection.addEventListener('open', ev => this.handleOpen(onOpenFunction));
        this.connection.addEventListener('error', ev => this.handleError(ev));
        this.connection.addEventListener('close', ev => this.handleClose());
    };

    this.sendChecked = function(message) {
        this.connection.send(message);
    };

    this.send = function(message) {
        if (this.connection && this.connection.readyState == WebSocket.OPEN) {
            this.sendChecked(message);
        }
    };

}

function handleMessage(message) {
    console.log("Server message: %s", message.data);
    try {
        const data = JSON.parse(message.data);
        if (data) {
            var action = data.action;
            console.log(`Action: ${action}`);
            if (action === 'initUser') {
                const userNameInput = $('#userName');
                userNameInput.value = data.userId;

                // Store the user-id in a session cookie.
                document.cookie = `MCP_PLAYGROUND_USER_ID=${encodeURIComponent(data.userId)}; Path=/; SameSite=Strict`;
            }
            else if (action === 'toolCall') {
                const toolRequestTextarea = $('#toolRequest');
                const wrapper = toolRequestTextarea.parentElement;

                toolRequestTextarea.value = JSON.stringify(data.toolRequest);

                // Remove any existing animation
                wrapper.classList.remove('animate-pulse');

                // Trigger reflow so we can re-apply the animation
                void wrapper.offsetWidth;

                // Add animation class
                wrapper.classList.add('animate-pulse');
            }
            else if (data.action === 'uiServerStarted') {
                const uiUrl = data.url;
                const iframe = $('#llm-ui-frame');
                iframe.src = uiUrl;
                $('#llm-ui-container').style.display = 'block';
                addMessage(`LLM UI bereit unter: ${uiUrl}`);
            }
        }
    } catch (e) {
        console.log("error in handleMessage", e);
        addMessage(`Server: ${message.data}`);
    }
}

/**
 * Initialization after initial DOM-build.
 */
function initPage() {
    var elMessages = $('#messages');
    if (!elMessages) {
        alert("Error in the page's HTML-code: #messages is missing.");
        return;
    }

    addMessage("Demo-Server started ...");

    function setPreset(presetNumber) {
        const presets = {
            1: {
                toolTitle: "get_weather",
                toolDescription: "This tool determines the weather at a given place. Weather forecast",
                propertyName: "Place",
                propertyDescription: "Name of the place where the weather is"
            },
            2: {
                toolTitle: "convert_currency",
                toolDescription: "Converts currency values between different currencies",
                propertyName: "Currency",
                propertyDescription: "The currency to convert from"
            },
            3: {
                toolTitle: "fetch_news",
                toolDescription: "Fetches latest news articles from various sources",
                propertyName: "Category",
                propertyDescription: "The news category to fetch"
            }
        };

        const preset = presets[presetNumber];
        if (preset) {
            $('#toolTitle').value = preset.toolTitle;
            $('#toolDescription').value = preset.toolDescription;
            $('#propertyName').value = preset.propertyName;
            $('#propertyDescription').value = preset.propertyDescription;

            // Clear Tool-Response und Response
            $('#toolRequest').value = '';
            $('#propertyValue').value = '';

            saveOrUpdateMcpServer();
        }
    }

    function saveOrUpdateMcpServer() {
        const userNameInput = $('#userName');
        const userName = userNameInput.value.trim() || 'Anonymous';
        const toolTitle = $('#toolTitle').value.trim();
        const toolDescription = $('#toolDescription').value.trim();
        const propertyName = $('#propertyName').value.trim();
        const propertyDescription = $('#propertyDescription').value.trim();

        // Build properties object
        const properties = {};
        if (propertyName) {
            properties[propertyName] = {
                description: propertyDescription,
                type: 'string'
            };
        }

        // Add second property if visible and filled
        if ($('#property2Section').style.display === 'flex') {
            const prop2Name = $('#property2Name').value.trim();
            const prop2Desc = $('#property2Description').value.trim();
            if (prop2Name) {
                properties[prop2Name] = {
                    description: prop2Desc || 'Additional property',
                    type: 'string'
                };
            }
        }

        const startMessage = {
            action: 'startMcp',
            userName: userName,
            tool: {
                title: toolTitle,
                description: toolDescription,
                properties: properties
            }
        };

        wsManager.send(JSON.stringify(startMessage));
        addMessage(`Sent: ${JSON.stringify(startMessage)}`);
    }

    function setupDynamicProperties() {
        const btnAdd = $('#btnAddProperty');
        const btnRemove = $('#btnRemoveProperty');
        const prop2Section = $('#property2Section');
        const prop2DescRow = $('#property2DescriptionRow');

        btnAdd.addEventListener('click', function () {
            prop2Section.style.display = 'flex';
            prop2DescRow.style.display = 'flex';
            btnAdd.disabled = true; // Disable [+] after adding
        });

        btnRemove.addEventListener('click', function () {
            prop2Section.style.display = 'none';
            prop2DescRow.style.display = 'none';
            $('#property2Name').value = '';
            $('#property2Description').value = '';
            btnAdd.disabled = false; // Re-enable [+] button
        });
    }

    $('#btnStartMcp').addEventListener("click", function(event) {
        saveOrUpdateMcpServer();
    });

    $('#btnSendResponse').addEventListener("click", function(event) {
        const userNameInput = $('#userName');
        const userName = userNameInput.value.trim() || 'Anonymous';
        const propertyValue = $('#propertyValue').value.trim();

        const respMessage = {
            action: 'toolResponse',
            userName: userName,
            toolResponse: {
              "type": "text",
              "text": propertyValue
            }
        };

        wsManager.send(JSON.stringify(respMessage));
        addMessage(`Sent: ${JSON.stringify(respMessage)}`);
    });

    // Preset Buttons
    $('#btnPreset1').addEventListener("click", function(event) {
        setPreset(1);
    });

    $('#btnPreset2').addEventListener("click", function(event) {
        setPreset(2);
    });

    $('#btnPreset3').addEventListener("click", function(event) {
        setPreset(3);
    });

    // Reset animation when focusing on propertyValue
    $('#propertyValue').addEventListener('focus', function () {
        const wrapper = $('#toolRequest').parentElement;
        wrapper.classList.remove('animate-pulse');
    });

    // Reset animation when clicking Send Response
    $('#btnSendResponse').addEventListener('click', function () {
        const wrapper = $('#toolRequest').parentElement;
        wrapper.classList.remove('animate-pulse');
    });

    $('#propertyValue').addEventListener('input', function () {
        const wrapper = $('#toolRequest').parentElement;
        if (this.value.trim().length > 0) {
            wrapper.classList.remove('animate-pulse');
        }
    });

    wsManager.initWebSocket(function() {
        addMessage("initWebSocket ...");

        // Send current user-id.
        const userNameInput = $('#userName');
        const userName = userNameInput.value.trim();

        const respMessage = {
            action: 'initUser',
            userName: userName,
        };

        wsManager.send(JSON.stringify(respMessage));
    });

    setupDynamicProperties();

    document.addEventListener('keydown', function(event) {
        // Check if Enter key is pressed and the focused element is the propertyValue-field.
        if (event.key === 'Enter' && event.target.id === 'propertyValue') {
            // Prevent default behavior (new line in textarea)
            event.preventDefault();

            $('#btnSendResponse').click();
        }
    });
}

document.addEventListener('DOMContentLoaded', initPage);
