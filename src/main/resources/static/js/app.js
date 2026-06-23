(function () {
  const SESSION_KEY = "graphrag.sessionId";
  const API_KEY_STORAGE = "graphrag.apiKey";
  const POLL_INTERVAL_MS = 2000;
  const UPLOAD_TIMEOUT_MS = 600000;
  const CHAT_TIMEOUT_MS = 180000;

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
  let deletingDocumentId = null;

  function buildHeaders(extra = {}) {
    const headers = { ...extra };
    const apiKey = sessionStorage.getItem(API_KEY_STORAGE);
    if (apiKey) {
      headers["X-API-Key"] = apiKey;
    }
    return headers;
  }

  async function ensureApiKey() {
    const config = await api("/api/ui-config", { skipApiKeyPrompt: true });
    if (!config.apiKeyRequired) return;
    if (sessionStorage.getItem(API_KEY_STORAGE)) return;

    const entered = window.prompt("Podaj klucz API (X-API-Key):");
    if (!entered) {
      throw new Error("Wymagany klucz API");
    }
    sessionStorage.setItem(API_KEY_STORAGE, entered.trim());
  }

  async function api(path, options = {}) {
    if (!options.skipApiKeyPrompt) {
      await ensureApiKey();
    }

    const headers = buildHeaders(
      options.body && !(options.body instanceof FormData)
        ? { "Content-Type": "application/json", ...options.headers }
        : { ...options.headers },
    );

    const controller = options.timeoutMs ? new AbortController() : null;
    const timeoutId = controller
      ? setTimeout(() => controller.abort(), options.timeoutMs)
      : null;

    let response;
    try {
      response = await fetch(path, {
        ...options,
        headers,
        signal: controller?.signal,
      });
    } catch (err) {
      if (err.name === "AbortError") {
        const timeoutErr = new Error("Przekroczono limit czasu odpowiedzi — spróbuj ponownie.");
        timeoutErr.status = 408;
        throw timeoutErr;
      }
      throw err;
    } finally {
      if (timeoutId) clearTimeout(timeoutId);
    }

    if (response.status === 401 && !options.skipApiKeyPrompt) {
      sessionStorage.removeItem(API_KEY_STORAGE);
      await ensureApiKey();
      return api(path, { ...options, skipApiKeyPrompt: true });
    }

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

  function appendMessage(role, text, meta = {}) {
    const el = document.createElement("div");
    el.className = `message message-${role}`;
    const textNode = document.createElement("div");
    textNode.textContent = text;
    el.appendChild(textNode);

    if (meta.sources && meta.sources.length > 0) {
      const sourcesBlock = document.createElement("details");
      sourcesBlock.className = "message-sources";

      const summary = document.createElement("summary");
      summary.className = "message-sources-summary";
      summary.textContent = `Źródła (${meta.sources.length})`;
      sourcesBlock.appendChild(summary);

      const list = document.createElement("div");
      list.className = "message-sources-list";
      meta.sources.forEach((source) => {
        const item = document.createElement("div");
        item.className = "message-source-item";
        const location = [source.filename, source.section, source.page ? `str. ${source.page}` : null]
          .filter(Boolean)
          .join(" · ");
        item.textContent = `[${source.index}] ${location} — ${source.excerpt}`;
        list.appendChild(item);
      });
      sourcesBlock.appendChild(list);
      el.appendChild(sourcesBlock);
    }

    if (meta.degraded) {
      const badge = document.createElement("div");
      badge.className = "message-degraded";
      badge.textContent = "Tryb ograniczony (graf niedostępny)";
      el.appendChild(badge);
    }

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
      if (deletingDocumentId === doc.id) {
        li.classList.add("document-item--deleting");
      }

      const main = document.createElement("div");
      main.className = "document-item-main";

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
      main.appendChild(name);
      main.appendChild(badges);

      const deleteBtn = document.createElement("button");
      deleteBtn.type = "button";
      deleteBtn.className = "btn btn-ghost btn-delete-document";
      deleteBtn.title = "Usuń dokument";
      deleteBtn.setAttribute("aria-label", `Usuń dokument ${doc.filename || doc.path}`);
      deleteBtn.textContent = "Usuń";
      deleteBtn.disabled = deletingDocumentId === doc.id;
      deleteBtn.addEventListener("click", () => deleteDocument(doc));

      li.appendChild(main);
      li.appendChild(deleteBtn);
      documentList.appendChild(li);
    });
  }

  async function deleteDocument(doc) {
    const label = doc.filename || doc.path;
    if (!window.confirm(`Usunąć „${label}”? Tej operacji nie można cofnąć.`)) {
      return;
    }

    deletingDocumentId = doc.id;
    await loadDocuments();

    try {
      await api(`/api/documents/${doc.id}`, { method: "DELETE" });
      setUploadStatus(`Usunięto: ${label}`, "success");
      appendSystemMessage(`Usunięto dokument „${label}”.`);
      await loadDocuments();
    } catch (err) {
      setUploadStatus(err.message || "Usuwanie nie powiodło się", "error");
    } finally {
      deletingDocumentId = null;
      await loadDocuments();
    }
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

    messageInput.value = "";
    appendMessage("user", content.trim());
    showLoading();

    try {
      await ensureSession();
      const result = await api(`/api/chat/sessions/${sessionId}/messages`, {
        method: "POST",
        body: JSON.stringify({ content: content.trim() }),
        timeoutMs: CHAT_TIMEOUT_MS,
      });
      removeLoading();
      appendMessage("assistant", result.answer, {
        sources: result.sources,
        degraded: result.degraded,
      });
    } catch (err) {
      removeLoading();
      if (err.status === 429) {
        appendSystemMessage("Za dużo zapytań — spróbuj za chwilę.");
      } else if (err.status === 503) {
        appendSystemMessage("Usługa AI chwilowo niedostępna — spróbuj ponownie za 30 s.");
      } else if (err.status === 408) {
        appendSystemMessage(err.message || "Przekroczono limit czasu odpowiedzi.");
      } else if (err.status === 500) {
        appendSystemMessage(err.message || "Wystąpił błąd podczas przetwarzania pytania.");
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
      let status;
      try {
        status = await api(`/api/jobs/${jobId}`);
      } catch (err) {
        if (err.status === 429) {
          const retryAfterSec = 5;
          setUploadStatus(`Limit zapytań — ponawiam za ${retryAfterSec} s…`, "indexing");
          await new Promise((r) => setTimeout(r, retryAfterSec * 1000));
          continue;
        }
        throw err;
      }
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
      await ensureApiKey();
      const formData = new FormData();
      formData.append("file", file);

      const response = await fetch("/api/documents/upload", {
        method: "POST",
        headers: buildHeaders(),
        body: formData,
      });

      if (response.status === 401) {
        sessionStorage.removeItem(API_KEY_STORAGE);
        throw new Error("Wymagany klucz API");
      }

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
      if (err.status === 429) {
        appendSystemMessage("Za dużo zapytań podczas indeksowania — spróbuj ponownie za minutę.");
      }
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
      await api("/api/ui-config", { skipApiKeyPrompt: true });
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
