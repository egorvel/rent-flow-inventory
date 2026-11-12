FROM python:3.14.0-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN pip install --no-cache-dir cryptography==50.0.2
WORKDIR /fixture
COPY fixture.py /fixture/fixture.py
USER 10001:10001
HEALTHCHECK --interval=1s --timeout=2s --retries=15 CMD ["python", "/fixture/fixture.py", "health"]
ENTRYPOINT ["python", "/fixture/fixture.py"]
CMD ["serve"]
