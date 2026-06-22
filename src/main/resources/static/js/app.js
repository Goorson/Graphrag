(function () {
  const SESSION_KEY = "graphrag.sessionId";
  const POLL_INTERVAL_MS = 2000;
  const UPLOAD_TIMEOUT_MS = 600000;

  const messagesEl = document.getElementById("messages");
  const messageInput = document.getElementById("messageInput");
  const chatForm = document.getElementById("chatForm");
  const sendBtn = document.getElementById("sendBtn");
  const newSessionBtn = document.getElementById("newSessionBtn");
  const documentList = document.getElementById("documentList");
  const dropZone = document.getElementById("dropZone");
  const fileInput = document.getElementById("fileInput");
  const uploadBtn = document.getElementById("uploadBtn");
  const uploadStatus = document.getElementById("uploadStatus");

  let sessionId = localStorage.getItem(SESSION_KEY);
  let isSending = false;
  let isUploading = false;

  async function api(path, options = {}) {
    const response = await fetch(path, {
      headers: { "Content-Type": "application/json", ...options.headers },
      ...options,
    });

    if (!response.ok) {
      let message = `Błąd ${response.status}`;
      try {
        const body = await response.json();
        if (body.error) message = body.error;
      } catch (_) {
        /* ignore */
      }
      const err = new Error(message);
      err.status = response.status;
      throw err;
    }

    if (response.status === 204) return null;
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
      return response.json();
    }
    return null;
  }

  function appendMessage(role, text) {
    const el = document.createElement("div");
    el.className = `message message-${role}`;
    el.textContent = text;
    messagesEl.appendChild(el);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return el;
  }

  function appendSystemMessage(text) {
    return appendMessage("system", text);
  }

  function showLoading() {
    const el = document.createElement("div");
    el.className = "message message-loading";
    el.id = "loadingIndicator";
    el.textContent = "Myślę…";
    messagesEl.appendChild(el);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return el;
  }

  function removeLoading() {
    const el = document.getElementById("loadingIndicator");
    if (el) el.remove();
  }

  function setUploadStatus(text, type) {
    uploadStatus.hidden = !text;
    uploadStatus.textContent = text || "";
    uploadStatus.className = "upload-status" + (type ? ` ${type}` : "");
  }

  function formatBadge(status, graphStatus) {
    const statusClass =
      status === "INDEXED"
        ? "badge-indexed"
        : status === "SKIPPED_OCR_REQUIRED"
          ? "badge-skipped"
          : "badge-pending";
    const graphClass =
      graphStatus === "INDEXED" ? "badge-graph" : graphStatus === "FAILED" ? "badge-failed" : "badge-pending";
    return { statusClass, graphClass };
  }

  function renderDocuments(docs) {
    documentList.innerHTML = "";
    if (!docs.length) {
      const li = document.createElement("li");
      li.className = "document-empty";
      li.textContent = "Brak dokumentów — dodaj PDF lub MD";
      documentList.appendChild(li);
      return;
    }

    docs.forEach((doc) => {
      const li = document.createElement("li");
      li.className = "document-item";

      const name = document.createElement("div");
      name.className = "document-name";
      name.textContent = doc.filename || doc.path;

      const badges = document.createElement("div");
      badges.className = "document-badges";
      const { statusClass, graphClass } = formatBadge(doc.status, doc.graphStatus);

      const statusBadge = document.createElement("span");
      statusBadge.className = `badge ${statusClass}`;
      statusBadge.textContent = doc.status;

      const graphBadge = document.createElement("span");
      graphBadge.className = `badge ${graphClass}`;
      graphBadge.textContent = `graf: ${doc.graphStatus}`;

      badges.appendChild(statusBadge);
      badges.appendChild(graphBadge);
      li.appendChild(name);
      li.appendChild(badges);
      documentList.appendChild(li);
    });
  }

  async function loadDocuments() {
    try {
      const docs = await api("/api/documents");
      renderDocuments(docs);
    } catch (err) {
      documentList.innerHTML = "";
      const li = document.createElement("li");
      li.className = "document-empty";
      li.textContent = "Nie udało się załadować dokumentów";
      documentList.appendChild(li);
    }
  }

  async function ensureSession() {
    if (sessionId) {
      try {
        await api(`/api/chat/sessions/${sessionId}/messages`);
        return sessionId;
      } catch (_) {
        localStorage.removeItem(SESSION_KEY);
        sessionId = null;
      }
    }

    const created = await api("/api/chat/sessions", { method: "POST", body: "{}" });
    sessionId = created.id;
    localStorage.setItem(SESSION_KEY, sessionId);
    return sessionId;
  }

  async function loadHistory() {
    if (!sessionId) return;
    try {
      const history = await api(`/api/chat/sessions/${sessionId}/messages`);
      messagesEl.innerHTML = "";
      history.forEach((msg) => {
        if (msg.role === "user" || msg.role === "assistant") {
          appendMessage(msg.role, msg.content);
        }
      });
      if (!history.length) {
        appendSystemMessage("Witaj! Dodaj materiały po lewej i zadaj pytanie.");
      }
    } catch (_) {
      appendSystemMessage("Nie udało się wczytać historii — rozpocznij nową rozmowę.");
    }
  }

  async function createNewSession() {
    localStorage.removeItem(SESSION_KEY);
    sessionId = null;
    messagesEl.innerHTML = "";
    await ensureSession();
    appendSystemMessage("Nowa rozmowa rozpoczęta.");
  }

  async function sendMessage(content) {
    if (!content.trim() || isSending) return;

    isSending = true;
    sendBtn.disabled = true;
    messageInput.disabled = true;

    appendMessage("user", content.trim());
    messageInput.value = "";
    showLoading();

    try {
      await ensureSession();
      const result = await api(`/api/chat/sessions/${sessionId}/messages`, {
        method: "POST",
        body: JSON.stringify({ content: content.trim() }),
      });
      removeLoading();
      appendMessage("assistant", result.answer);
    } catch (err) {
      removeLoading();
      if (err.status === 429) {
        appendSystemMessage("Za dużo zapytań — spróbuj za chwilę.");
      } else if (err.status === 503) {
        appendSystemMessage("Usługa AI chwilowo niedostępna — spróbuj ponownie za 30 s.");
      } else {
        appendSystemMessage(err.message || "Wystąpił błąd podczas wysyłania wiadomości.");
      }
    } finally {
      isSending = false;
      sendBtn.disabled = false;
      messageInput.disabled = false;
      messageInput.focus();
    }
  }

  async function pollJob(jobId) {
    const deadline = Date.now() + UPLOAD_TIMEOUT_MS;
    while (Date.now() < deadline) {
      const status = await api(`/api/jobs/${jobId}`);
      setUploadStatus(`Indeksowanie… ${status.progressPct}% (${status.status})`, "indexing");

      if (status.status === "DONE") return status;
      if (status.status === "FAILED") {
        throw new Error(status.errorMessage || "Indeksowanie nie powiodło się");
      }
      await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
    }
    throw new Error("Przekroczono limit czasu indeksowania");
  }

  async function uploadFile(file) {
    if (!file || isUploading) return;

    const name = file.name.toLowerCase();
    if (!name.endsWith(".pdf") && !name.endsWith(".md")) {
      setUploadStatus("Obsługiwane formaty: PDF i MD", "error");
      return;
    }

    isUploading = true;
    uploadBtn.disabled = true;
    setUploadStatus(`Wysyłanie ${file.name}…`, "indexing");

    try {
      const formData = new FormData();
      formData.append("file", file);

      const response = await fetch("/api/documents/upload", {
        method: "POST",
        body: formData,
      });

      if (!response.ok) {
        let message = `Błąd uploadu (${response.status})`;
        try {
          const body = await response.json();
          if (body.error) message = body.error;
        } catch (_) {
          /* ignore */
        }
        throw new Error(message);
      }

      const job = await response.json();
      await pollJob(job.jobId);

      setUploadStatus(`Gotowe: ${file.name}`, "success");
      await loadDocuments();
      appendSystemMessage(`Dodano plik „${file.name}” — możesz pytać o jego treść.`);
    } catch (err) {
      setUploadStatus(err.message || "Upload nie powiódł się", "error");
    } finally {
      isUploading = false;
      uploadBtn.disabled = false;
      fileInput.value = "";
    }
  }

  function handleFiles(files) {
    if (files && files.length > 0) {
      uploadFile(files[0]);
    }
  }

  chatForm.addEventListener("submit", (e) => {
    e.preventDefault();
    sendMessage(messageInput.value);
  });

  messageInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage(messageInput.value);
    }
  });

  newSessionBtn.addEventListener("click", createNewSession);

  uploadBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => handleFiles(fileInput.files));

  dropZone.addEventListener("click", () => fileInput.click());
  dropZone.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      fileInput.click();
    }
  });

  ["dragenter", "dragover"].forEach((evt) => {
    dropZone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropZone.classList.add("drag-over");
    });
  });

  ["dragleave", "drop"].forEach((evt) => {
    dropZone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropZone.classList.remove("drag-over");
      if (evt === "drop") handleFiles(e.dataTransfer.files);
    });
  });

  async function init() {
    try {
      await ensureSession();
      await loadHistory();
      await loadDocuments();
    } catch (err) {
      appendSystemMessage("Nie udało się połączyć z serwerem. Uruchom aplikację (bootRun).");
    }
    messageInput.focus();
  }

  init();
})();
