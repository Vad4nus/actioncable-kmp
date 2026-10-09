module ApplicationCable
  class Connection < ActionCable::Connection::Base
    NONCES = Concurrent::Set.new

    identified_by :client_id

    def connect
      sleep(request.params[:delay].to_f) if request.params[:delay]
      reject_unauthorized_connection if token == "invalid" || replayed_nonce?
      self.client_id = request.params[:client_id] || SecureRandom.uuid
    end

    private

    def token
      request.params[:token] || request.authorization&.delete_prefix("Bearer ") || cookies[:token]
    end

    def replayed_nonce?
      nonce = request.params[:nonce]
      nonce.present? && !NONCES.add?(nonce)
    end
  end
end
